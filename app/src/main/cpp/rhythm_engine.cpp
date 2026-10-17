#include <jni.h>
#include <oboe/Oboe.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>

namespace {
constexpr int kVoices = 6; // five event voices plus post-mix master
class Engine final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
 public:
  bool start() {
    std::lock_guard<std::mutex> guard(lifecycle_);
    if (stream_) return true;
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Shared)->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(oboe::ChannelCount::Mono)->setUsage(oboe::Usage::Game)
      ->setContentType(oboe::ContentType::Sonification)->setDataCallback(this)->setErrorCallback(this);
    std::shared_ptr<oboe::AudioStream> s;
    if (b.openStream(s) != oboe::Result::OK) return false;
    sampleRate_ = s->getSampleRate(); cursor_ = 0; active_.fill(false);
    nextBeatFrame_ = 0; beatIndex_ = 0; lastTempo_ = bpm_.load(); nextSubFrame_.fill(0); subIndex_.fill(0);
    if (s->requestStart() != oboe::Result::OK) { s->close(); return false; }
    stream_ = std::move(s); return true;
  }
  void stop() {
    std::lock_guard<std::mutex> guard(lifecycle_);
    playing_.store(false, std::memory_order_release);
    if (stream_) { stream_->requestStop(); stream_->close(); stream_.reset(); }
  }
  void configure(double bpm, const std::array<float,6>& levels) {
    bpm_.store(std::clamp(bpm,30.0,250.0), std::memory_order_relaxed);
    for(int i=0;i<6;i++) levels_[i].store(std::clamp(levels[i],0.0f,1.0f), std::memory_order_relaxed);
  }
  void play(bool on) { playing_.store(on, std::memory_order_release); }
  void reset() { stageComplete_.store(false, std::memory_order_release); resetRequested_.store(true, std::memory_order_release); }
  bool consumeStageComplete() { return stageComplete_.exchange(false, std::memory_order_acq_rel); }
  void setBarBeats(int beats) { barBeats_.store(std::clamp(beats,0,9),std::memory_order_relaxed); }
  void setSound(int sound) { sound_.store(std::clamp(sound,1,2),std::memory_order_relaxed); }
  void setMode(int mode) { mode_.store(std::clamp(mode,0,1),std::memory_order_relaxed); }
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
    auto* out = static_cast<float*>(data);
    const int64_t begin = cursor_;
    if(resetRequested_.exchange(false, std::memory_order_acq_rel)) { nextBeatFrame_=begin; beatIndex_=0; stageBeatCount_=0; stageRun_=mode_.load(std::memory_order_relaxed)==1; nextSubFrame_.fill(begin); subIndex_.fill(0); active_.fill(false); }
    const bool playing=playing_.load(std::memory_order_acquire);
    if(playing) schedule(begin,begin+frames);
    else active_.fill(false);
    const float master=levels_[5].load(std::memory_order_relaxed);
    for(int32_t i=0;i<frames;i++) {
      const int64_t now=begin+i;
      float mixed=0.0f;
      for(int v=0;v<5;v++) {
        if(active_[v] && now>=endFrame_[v]) active_[v]=false;
        if(active_[v]) {
          const float phase=static_cast<float>(now-startFrame_[v])/static_cast<float>(sampleRate_);
          const float fundamental=2.0f*static_cast<float>(M_PI)*frequency_[v]*phase;
          const bool bright=timbre_[v]==1;
          const float wave=bright ? (0.76f*std::sin(fundamental)+0.18f*std::sin(2.0f*fundamental)+0.06f*std::sin(3.0f*fundamental)) : std::sin(fundamental);
          const float env=std::exp(-phase*(bright?165.0f:105.0f));
          mixed += wave*env*eventGain_[v];
        }
      }
      out[i]=std::tanh(mixed*master*0.72f);
    }
    cursor_+=frames;
    return oboe::DataCallbackResult::Continue;
  }
  void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override { playing_.store(false); }
 private:
  void trigger(int voice,int64_t frame,float gain,float hz) {
    const float channel=levels_[voice].load(std::memory_order_relaxed);
    active_[voice]=channel>0.0001f && gain>0.0001f;
    startFrame_[voice]=frame;
    endFrame_[voice]=frame+static_cast<int64_t>(sampleRate_*0.055);
    eventGain_[voice]=gain*channel;
    const int timbre=sound_.load(std::memory_order_relaxed);
    timbre_[voice]=timbre;
    if(timbre==2) {
      if(voice==0 || voice==1 || voice==2) hz=987.7666f; // B5
      else if(voice==3) hz=493.8833f; // B4
      // Voice 4 (the beat accent) deliberately keeps its Sound 1 pitch.
    }
    frequency_[voice]=hz;
  }
  void schedule(int64_t begin,int64_t end) {
    const double tempo=bpm_.load(std::memory_order_relaxed);
    const int64_t beatFrames=std::max<int64_t>(1,static_cast<int64_t>(sampleRate_*60.0/tempo));
    if(tempo!=lastTempo_) {
      const int64_t oldBeat=std::max<int64_t>(1,static_cast<int64_t>(sampleRate_*60.0/lastTempo_));
      const double beatPhase=std::clamp(static_cast<double>(nextBeatFrame_-begin)/oldBeat,0.0,1.0);
      nextBeatFrame_=begin+static_cast<int64_t>(beatPhase*beatFrames);
      for(int d=2;d<=4;d++) {
        const int64_t oldStep=std::max<int64_t>(1,oldBeat/d);
        const int64_t newStep=std::max<int64_t>(1,beatFrames/d);
        const double phase=static_cast<double>(std::max<int64_t>(0,nextSubFrame_[d]-begin))/oldStep;
        nextSubFrame_[d]=begin+static_cast<int64_t>(phase*newStep);
      }
      lastTempo_=tempo;
    }
    while(nextBeatFrame_<end) {
      if(nextBeatFrame_>=begin) {
        const int beatsPerBar = barBeats_.load(std::memory_order_relaxed);
        const int stageBeatsPerBar=std::max(1,beatsPerBar); // Beat 0 has no physical downbeat marker; Stage uses one click per bar.
        if(stageRun_ && stageBeatCount_>=8*stageBeatsPerBar) {
          playing_.store(false,std::memory_order_release);
          stageComplete_.store(true,std::memory_order_release);
          active_.fill(false);
          return;
        }
        trigger(0,nextBeatFrame_,1.0f,2217.461f); // C#7
        const bool downbeat=beatsPerBar>0 && (beatIndex_%beatsPerBar)==0;
        if(downbeat) trigger(4,nextBeatFrame_,1.0f,2959.955f); // F#7, same nominal gain as the rhythm voices.
        beatIndex_++;
        if(stageRun_) stageBeatCount_++;
      }
      nextBeatFrame_+=beatFrames;
    }
    for(int div=2;div<=4;div++) {
      // Mixer order: quarter(0), eighth(1), sixteenth(2), triplet(3), beat(4), master(5).
      const int voice=(div==2?1:(div==3?3:2));
      const int64_t step=std::max<int64_t>(1,beatFrames/div);
      if(nextSubFrame_[div]<begin) {
        const int64_t missed=(begin-nextSubFrame_[div]+step-1)/step;
        nextSubFrame_[div]+=missed*step; subIndex_[div]+=missed;
      }
      while(nextSubFrame_[div]<end) {
        // Eighths add only the offbeat. Sixteenths add the 2nd and 4th
        // sixteenth positions; the existing triplet pattern is unchanged.
        const int position = static_cast<int>(subIndex_[div] % div);
        const bool audiblePosition = div==2 ? position==1
            : (div==4 ? (position==1 || position==3) : true);
        if(audiblePosition) trigger(voice,nextSubFrame_[div],1.0f,div==3?1108.731f:2217.461f); // Triplet C#6; other subdivisions C#7.
        nextSubFrame_[div]+=step; subIndex_[div]++;
      }
    }
  }
  std::shared_ptr<oboe::AudioStream> stream_;
  std::mutex lifecycle_;
  std::atomic<bool> playing_{false},resetRequested_{false},stageComplete_{false};
  std::atomic<double> bpm_{120.0};
  std::atomic<int> barBeats_{4},sound_{1},mode_{0};
  std::array<std::atomic<float>,6> levels_{};
  std::array<bool,kVoices> active_{};
  std::array<int64_t,kVoices> startFrame_{},endFrame_{};
  std::array<float,kVoices> eventGain_{},frequency_{};
  std::array<int,kVoices> timbre_{};
  std::array<int64_t,5> nextSubFrame_{}; std::array<int64_t,5> subIndex_{};
  int64_t nextBeatFrame_=0,beatIndex_=0,cursor_=0; int32_t sampleRate_=48000; double lastTempo_=120.0; int stageBeatCount_=0; bool stageRun_=false;
};
Engine engine;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_rhythmwatch_AudioEngine_nativeStart(JNIEnv*,jobject){ return engine.start(); }
extern "C" JNIEXPORT void JNICALL Java_com_rhythmwatch_AudioEngine_nativeStop(JNIEnv*,jobject){ engine.stop(); }
extern "C" JNIEXPORT void JNICALL Java_com_rhythmwatch_AudioEngine_nativePlay(JNIEnv*,jobject,jboolean p){ engine.play(p); }
extern "C" JNIEXPORT void JNICALL Java_com_rhythmwatch_AudioEngine_nativeReset(JNIEnv*,jobject){ engine.reset(); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_rhythmwatch_AudioEngine_nativeConsumeStageComplete(JNIEnv*,jobject){ return engine.consumeStageComplete(); }
extern "C" JNIEXPORT void JNICALL Java_com_rhythmwatch_AudioEngine_nativeSetBeat(JNIEnv*,jobject,jint beats){ engine.setBarBeats(beats); }
extern "C" JNIEXPORT void JNICALL Java_com_rhythmwatch_AudioEngine_nativeSetSound(JNIEnv*,jobject,jint sound){ engine.setSound(sound); }
extern "C" JNIEXPORT void JNICALL Java_com_rhythmwatch_AudioEngine_nativeSetMode(JNIEnv*,jobject,jint mode){ engine.setMode(mode); }
extern "C" JNIEXPORT void JNICALL Java_com_rhythmwatch_AudioEngine_nativeConfigure(JNIEnv* env,jobject,jdouble bpm,jfloatArray array){
  jfloat* p=env->GetFloatArrayElements(array,nullptr); std::array<float,6> levels{};
  for(int i=0;i<6;i++) levels[i]=p[i]; env->ReleaseFloatArrayElements(array,p,JNI_ABORT); engine.configure(bpm,levels);
}
