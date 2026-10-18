#include "rhythm_renderer.h"

#include <algorithm>
#include <cmath>

namespace rhythmwatch {
namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr int kQuarter = 0;
constexpr int kEighth = 1;
constexpr int kSixteenth = 2;
constexpr int kTriplet = 3;
constexpr int kBeatAccent = 4;
constexpr int kMaster = 5;
constexpr float kClickGain = 1.0f;
}

int64_t RhythmRenderer::gridFrame(int64_t origin, uint64_t ordinal, int divisor,
                                  double beatPeriodFrames) {
  if (divisor <= 0) return origin;
  return static_cast<int64_t>(std::llround(
      static_cast<double>(origin) + static_cast<double>(ordinal) *
                                        beatPeriodFrames / divisor));
}

void RhythmRenderer::setSampleRate(int32_t sampleRate) {
  sampleRate_ = std::max<int32_t>(1, sampleRate);
}

void RhythmRenderer::begin(int64_t frame, const RendererConfig& config) {
  cursor_ = frame;
  gridOrigin_ = frame;
  beatPeriodFrames_ = sampleRate_ * 60.0 / std::clamp(config.bpm, 30.0, 250.0);
  lastTempo_ = std::clamp(config.bpm, 30.0, 250.0);
  nextOrdinal_.fill(0);
  beatIndex_ = 0;
  stageBeatCount_ = 0;
  stageRun_ = config.mode == 1;
  stageComplete_ = false;
  initialized_ = true;
  stop();
}

void RhythmRenderer::stop() {
  for (auto& note : notes_) note.voice = -1;
}

void RhythmRenderer::changeTempo(int64_t frame, double newPeriodFrames) {
  const double oldPosition = static_cast<double>(frame - gridOrigin_) / beatPeriodFrames_;
  const double fractionalBeat = oldPosition - std::floor(oldPosition);
  const double epsilon = 1.0e-9;
  gridOrigin_ = frame - static_cast<int64_t>(std::llround(fractionalBeat * newPeriodFrames));
  beatPeriodFrames_ = newPeriodFrames;
  for (int stream = 0; stream < 4; ++stream) {
    const int divisor = kDivisors[stream];
    if (fractionalBeat < epsilon || 1.0 - fractionalBeat < epsilon) {
      nextOrdinal_[stream] = 0;
    } else {
      nextOrdinal_[stream] = static_cast<uint64_t>(std::floor(fractionalBeat * divisor)) + 1;
    }
  }
}

int64_t RhythmRenderer::eventFrame(int stream, double beatPeriodFrames) const {
  return gridFrame(gridOrigin_, nextOrdinal_[stream], kDivisors[stream], beatPeriodFrames);
}

void RhythmRenderer::trigger(int voice, int64_t frame, float gain, float frequency,
                             const RendererConfig& config, EventObserver observer,
                             void* observerContext) {
  const float channel = std::clamp(config.levels[voice], 0.0f, 1.0f);
  const float eventGain = gain * channel;
  const int timbre = std::clamp(config.sound, 1, 2);
  if (observer != nullptr) {
    observer(ScheduledEvent{frame, voice, eventGain, frequency, timbre}, observerContext);
  }
  if (channel <= 0.0001f || gain <= 0.0001f) return;

  auto slot = std::find_if(notes_.begin(), notes_.end(), [](const Note& note) {
    return note.voice < 0;
  });
  if (slot == notes_.end()) {
    slot = std::min_element(notes_.begin(), notes_.end(), [](const Note& left, const Note& right) {
      return left.endFrame < right.endFrame;
    });
  }
  if (timbre == 2) {
    if (voice == kQuarter || voice == kEighth || voice == kSixteenth) frequency = 987.7666f;
    else if (voice == kTriplet) frequency = 493.8833f;
  }
  *slot = Note{voice, frame,
               frame + static_cast<int64_t>(sampleRate_ * 0.055),
               eventGain, frequency, timbre};
}

void RhythmRenderer::emitEvent(int stream, int64_t frame, const RendererConfig& config,
                               EventObserver observer, void* observerContext) {
  const uint64_t ordinal = nextOrdinal_[stream]++;
  const int divisor = kDivisors[stream];
  if (stream == 0) {
    const int beatsPerBar = std::clamp(config.barBeats, 0, 9);
    const int stageBeatsPerBar = std::max(1, beatsPerBar);
    if (stageRun_ && stageBeatCount_ >= 8 * stageBeatsPerBar) {
      stageComplete_ = true;
      stop();
      return;
    }
    trigger(kQuarter, frame, kClickGain, 2217.461f, config, observer, observerContext);
    const bool downbeat = beatsPerBar > 0 && (beatIndex_ % beatsPerBar) == 0;
    if (downbeat) {
      trigger(kBeatAccent, frame, kClickGain, 2959.955f, config, observer, observerContext);
    }
    ++beatIndex_;
    if (stageRun_) ++stageBeatCount_;
    return;
  }

  int voice = kTriplet;
  if (divisor == 2) voice = kEighth;
  else if (divisor == 4) voice = kSixteenth;
  const int position = static_cast<int>(ordinal % static_cast<uint64_t>(divisor));
  const bool audible = divisor == 2 ? position == 1
      : (divisor == 4 ? (position == 1 || position == 3) : true);
  if (audible) {
    trigger(voice, frame, kClickGain, divisor == 3 ? 1108.731f : 2217.461f,
            config, observer, observerContext);
  }
}

bool RhythmRenderer::render(float* output, int32_t frames, const RendererConfig& config,
                            EventObserver observer, void* observerContext) {
  if (output == nullptr || frames <= 0) return stageComplete_;
  if (!initialized_) begin(cursor_, config);

  const double tempo = std::clamp(config.bpm, 30.0, 250.0);
  if (tempo != lastTempo_) {
    changeTempo(cursor_, sampleRate_ * 60.0 / tempo);
    lastTempo_ = tempo;
  }

  const int64_t blockEnd = cursor_ + frames;
  for (int32_t index = 0; index < frames; ++index) {
    const int64_t now = cursor_ + index;
    while (!stageComplete_) {
      int nextStream = -1;
      int64_t nextFrame = blockEnd;
      for (int stream = 0; stream < 4; ++stream) {
        const int64_t candidate = eventFrame(stream, beatPeriodFrames_);
        if (candidate < nextFrame) {
          nextFrame = candidate;
          nextStream = stream;
        }
      }
      if (nextStream < 0 || nextFrame > now) break;
      emitEvent(nextStream, nextFrame, config, observer, observerContext);
    }

    float mixed = 0.0f;
    for (auto& note : notes_) {
      if (note.voice < 0) continue;
      if (now >= note.endFrame) {
        note.voice = -1;
        continue;
      }
      const float phase = static_cast<float>(now - note.startFrame) / sampleRate_;
      const float fundamental = 2.0f * kPi * note.frequency * phase;
      const bool bright = note.timbre == 1;
      const float wave = bright
          ? (0.76f * std::sin(fundamental) + 0.18f * std::sin(2.0f * fundamental) +
             0.06f * std::sin(3.0f * fundamental))
          : std::sin(fundamental);
      const float envelope = std::exp(-phase * (bright ? 165.0f : 105.0f));
      mixed += wave * envelope * note.gain;
    }
    const float master = std::clamp(config.levels[kMaster], 0.0f, 1.0f);
    output[index] = std::tanh(mixed * master * 0.72f);
  }
  cursor_ = blockEnd;
  return stageComplete_;
}

}  // namespace rhythmwatch
