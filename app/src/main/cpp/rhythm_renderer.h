#pragma once

#include <array>
#include <atomic>
#include <cstdint>

namespace rhythmwatch {

struct RendererConfig {
  double bpm = 120.0;
  std::array<float, 6> levels{};
  int barBeats = 4;
  int sound = 1;
  int mode = 0;
};

struct ScheduledEvent {
  int64_t frame;
  int voice;
  float gain;
  float frequency;
  int timbre;
};

using EventObserver = void (*)(const ScheduledEvent&, void*);

inline bool takeResetIfPlaying(std::atomic<bool>& resetRequested, bool playing) {
  return playing && resetRequested.exchange(false, std::memory_order_acq_rel);
}

class RhythmRenderer final {
 public:
  void setSampleRate(int32_t sampleRate);
  void begin(int64_t frame, const RendererConfig& config);
  void stop();
  bool render(float* output, int32_t frames, const RendererConfig& config,
              EventObserver observer = nullptr, void* observerContext = nullptr);
  bool stageComplete() const { return stageComplete_; }

  static int64_t gridFrame(int64_t origin, uint64_t ordinal, int divisor,
                           double beatPeriodFrames);

 private:
  struct Note {
    int voice = -1;
    int64_t startFrame = 0;
    int64_t endFrame = 0;
    float gain = 0.0f;
    float frequency = 0.0f;
    int timbre = 1;
  };

  int64_t eventFrame(int stream, double beatPeriodFrames) const;
  void emitEvent(int stream, int64_t frame, const RendererConfig& config,
                 EventObserver observer, void* observerContext);
  void trigger(int voice, int64_t frame, float gain, float frequency,
               const RendererConfig& config, EventObserver observer,
               void* observerContext);
  void changeTempo(int64_t frame, double newPeriodFrames);

  static constexpr int kNoteSlots = 32;
  static constexpr std::array<int, 4> kDivisors{1, 2, 4, 3};
  std::array<Note, kNoteSlots> notes_{};
  int32_t sampleRate_ = 48000;
  int64_t cursor_ = 0;
  int64_t gridOrigin_ = 0;
  std::array<uint64_t, 4> nextOrdinal_{};
  double lastTempo_ = 120.0;
  double beatPeriodFrames_ = 24000.0;
  int64_t beatIndex_ = 0;
  int stageBeatCount_ = 0;
  bool stageRun_ = false;
  bool stageComplete_ = false;
  bool initialized_ = false;
};

}  // namespace rhythmwatch
