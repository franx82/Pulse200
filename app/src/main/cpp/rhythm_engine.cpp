#include <jni.h>
#include <oboe/Oboe.h>

#include "rhythm_renderer.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>

namespace {
class Engine final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
 public:
  bool start() {
    std::lock_guard<std::mutex> guard(lifecycle_);
    if (stream_ && !streamInvalid_.load(std::memory_order_acquire)) return true;
    if (stream_) {
      if (!streamInvalid_.load(std::memory_order_acquire)) stream_->close();
      stream_.reset();
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setUsage(oboe::Usage::Game)
        ->setContentType(oboe::ContentType::Sonification)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    if (builder.openStream(stream) != oboe::Result::OK) return false;
    renderer_.setSampleRate(stream->getSampleRate());
    sampleRate_.store(stream->getSampleRate(), std::memory_order_relaxed);
    framesPerBurst_.store(stream->getFramesPerBurst(), std::memory_order_relaxed);
    bufferSize_.store(stream->getBufferSizeInFrames(), std::memory_order_relaxed);
    cursor_ = 0;
    renderer_.stop();
    if (stream->requestStart() != oboe::Result::OK) {
      stream->close();
      return false;
    }
    stream_ = std::move(stream);
    streamClosing_.store(false, std::memory_order_release);
    streamInvalid_.store(false, std::memory_order_release);
    streamFailed_.store(false, std::memory_order_release);
    return true;
  }

  void stop() {
    std::lock_guard<std::mutex> guard(lifecycle_);
    playing_.store(false, std::memory_order_release);
    if (stream_) {
      if (!streamInvalid_.load(std::memory_order_acquire)) {
        stream_->requestStop();
        stream_->close();
      }
      stream_.reset();
    }
  }

  void configure(double bpm, const std::array<float, 6>& levels) {
    if (!std::isfinite(bpm)) return;
    bpm_.store(std::clamp(bpm, 30.0, 250.0), std::memory_order_relaxed);
    for (int index = 0; index < 6; ++index) {
      if (!std::isfinite(levels[index])) return;
    }
    for (int index = 0; index < 6; ++index) {
      levels_[index].store(std::clamp(levels[index], 0.0f, 1.0f), std::memory_order_relaxed);
    }
  }

  void play(bool on) {
    if (on) {
      if (!playing_.exchange(true, std::memory_order_acq_rel)) {
        stageComplete_.store(false, std::memory_order_release);
        resetRequested_.store(true, std::memory_order_release);
      }
    } else {
      playing_.store(false, std::memory_order_release);
    }
  }

  void reset() {
    stageComplete_.store(false, std::memory_order_release);
    resetRequested_.store(true, std::memory_order_release);
  }

  bool consumeStageComplete() { return stageComplete_.exchange(false, std::memory_order_acq_rel); }
  bool consumeStreamFailure() { return streamFailed_.exchange(false, std::memory_order_acq_rel); }
  bool isPlaying() const { return playing_.load(std::memory_order_acquire); }

  std::string diagnostics() {
    std::lock_guard<std::mutex> lifecycleGuard(lifecycle_);
    std::lock_guard<std::mutex> diagnosticsGuard(diagnosticsMutex_);
    int32_t xruns = lastXRunCount_.load(std::memory_order_relaxed);
    int32_t state = -1;
    if (stream_ && !streamClosing_.load(std::memory_order_acquire)) {
      state = static_cast<int>(stream_->getState());
      const auto result = stream_->getXRunCount();
      if (result) {
        xruns = result.value();
        lastXRunCount_.store(xruns, std::memory_order_relaxed);
      }
    }
    std::ostringstream message;
    message << "sampleRate=" << sampleRate_.load(std::memory_order_relaxed)
            << " framesPerBurst=" << framesPerBurst_.load(std::memory_order_relaxed)
            << " bufferFrames=" << bufferSize_.load(std::memory_order_relaxed)
            << " xruns=" << xruns
            << " streamState=" << state;
    return message.str();
  }

  void setBarBeats(int beats) { barBeats_.store(std::clamp(beats, 0, 9), std::memory_order_relaxed); }
  void setSound(int sound) { sound_.store(std::clamp(sound, 1, 2), std::memory_order_relaxed); }
  void setMode(int mode) { mode_.store(std::clamp(mode, 0, 1), std::memory_order_relaxed); }

  oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
    auto* output = static_cast<float*>(data);
    if (output == nullptr || frames <= 0) return oboe::DataCallbackResult::Continue;

    const bool playing = playing_.load(std::memory_order_acquire);
    if (rhythmwatch::takeResetIfPlaying(resetRequested_, playing)) {
      renderer_.begin(cursor_, readConfig());
    }
    if (!playing) {
      renderer_.stop();
      std::fill(output, output + frames, 0.0f);
      cursor_ += frames;
      return oboe::DataCallbackResult::Continue;
    }

    const bool completed = renderer_.render(output, frames, readConfig());
    if (completed) {
      playing_.store(false, std::memory_order_release);
      stageComplete_.store(true, std::memory_order_release);
    }
    cursor_ += frames;
    return oboe::DataCallbackResult::Continue;
  }

  void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override {
    playing_.store(false, std::memory_order_release);
    streamClosing_.store(true, std::memory_order_release);
    streamInvalid_.store(true, std::memory_order_release);
    streamFailed_.store(true, std::memory_order_release);
  }

  void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result) override {
    std::lock_guard<std::mutex> guard(diagnosticsMutex_);
    streamClosing_.store(true, std::memory_order_release);
    if (stream != nullptr) {
      const auto result = stream->getXRunCount();
      if (result) lastXRunCount_.store(result.value(), std::memory_order_relaxed);
    }
  }

 private:
  rhythmwatch::RendererConfig readConfig() const {
    rhythmwatch::RendererConfig config;
    config.bpm = bpm_.load(std::memory_order_relaxed);
    config.barBeats = barBeats_.load(std::memory_order_relaxed);
    config.sound = sound_.load(std::memory_order_relaxed);
    config.mode = mode_.load(std::memory_order_relaxed);
    for (int index = 0; index < 6; ++index) {
      config.levels[index] = levels_[index].load(std::memory_order_relaxed);
    }
    return config;
  }

  std::shared_ptr<oboe::AudioStream> stream_;
  std::mutex lifecycle_;
  std::mutex diagnosticsMutex_;
  std::atomic<bool> playing_{false};
  std::atomic<bool> resetRequested_{false};
  std::atomic<bool> stageComplete_{false};
  std::atomic<bool> streamFailed_{false};
  std::atomic<bool> streamInvalid_{false};
  std::atomic<bool> streamClosing_{false};
  std::atomic<double> bpm_{120.0};
  std::atomic<int> barBeats_{4};
  std::atomic<int> sound_{1};
  std::atomic<int> mode_{0};
  std::atomic<int32_t> sampleRate_{48000};
  std::atomic<int32_t> framesPerBurst_{0};
  std::atomic<int32_t> bufferSize_{0};
  std::atomic<int32_t> lastXRunCount_{0};
  std::array<std::atomic<float>, 6> levels_{};
  rhythmwatch::RhythmRenderer renderer_;
  int64_t cursor_ = 0;
};

Engine engine;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rhythmwatch_AudioEngine_nativeStart(JNIEnv*, jobject) {
  return engine.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhythmwatch_AudioEngine_nativeStop(JNIEnv*, jobject) {
  engine.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhythmwatch_AudioEngine_nativePlay(JNIEnv*, jobject, jboolean playing) {
  engine.play(playing == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhythmwatch_AudioEngine_nativeReset(JNIEnv*, jobject) {
  engine.reset();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rhythmwatch_AudioEngine_nativeConsumeStageComplete(JNIEnv*, jobject) {
  return engine.consumeStageComplete() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rhythmwatch_AudioEngine_nativeConsumeStreamFailure(JNIEnv*, jobject) {
  return engine.consumeStreamFailure() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rhythmwatch_AudioEngine_nativeIsPlaying(JNIEnv*, jobject) {
  return engine.isPlaying() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rhythmwatch_AudioEngine_nativeDiagnostics(JNIEnv* env, jobject) {
  const std::string message = engine.diagnostics();
  return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhythmwatch_AudioEngine_nativeSetBeat(JNIEnv*, jobject, jint beats) {
  engine.setBarBeats(beats);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhythmwatch_AudioEngine_nativeSetSound(JNIEnv*, jobject, jint sound) {
  engine.setSound(sound);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhythmwatch_AudioEngine_nativeSetMode(JNIEnv*, jobject, jint mode) {
  engine.setMode(mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhythmwatch_AudioEngine_nativeConfigure(JNIEnv* env, jobject, jdouble bpm,
                                                 jfloatArray array) {
  if (array == nullptr || env->GetArrayLength(array) != 6 || !std::isfinite(bpm)) return;
  std::array<jfloat, 6> values{};
  env->GetFloatArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
  if (env->ExceptionCheck()) return;
  std::array<float, 6> levels{};
  for (size_t index = 0; index < values.size(); ++index) {
    if (!std::isfinite(values[index])) return;
    levels[index] = values[index];
  }
  engine.configure(bpm, levels);
}
