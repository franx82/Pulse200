#include "../../app/src/main/cpp/rhythm_renderer.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {
using rhythmwatch::RendererConfig;
using rhythmwatch::RhythmRenderer;
using rhythmwatch::ScheduledEvent;

void collect(const ScheduledEvent& event, void* context) {
  static_cast<std::vector<ScheduledEvent>*>(context)->push_back(event);
}

RendererConfig config(double bpm = 120.0, int mode = 0) {
  RendererConfig result;
  result.bpm = bpm;
  result.levels.fill(1.0f);
  result.barBeats = 4;
  result.sound = 1;
  result.mode = mode;
  return result;
}

void eventTimingAndSameVoiceEvents() {
  RhythmRenderer renderer;
  renderer.setSampleRate(48000);
  auto settings = config();
  renderer.begin(0, settings);
  std::vector<ScheduledEvent> events;
  std::array<float, 48000> output{};
  renderer.render(output.data(), static_cast<int32_t>(output.size()), settings, collect, &events);

  std::vector<int64_t> quarterFrames;
  std::vector<int64_t> eighthFrames;
  std::vector<int64_t> sixteenthFrames;
  std::vector<int64_t> tripletFrames;
  for (const auto& event : events) {
    assert(event.frame >= 0 && event.frame < static_cast<int64_t>(output.size()));
    if (event.voice == 0) quarterFrames.push_back(event.frame);
    if (event.voice == 1) eighthFrames.push_back(event.frame);
    if (event.voice == 2) sixteenthFrames.push_back(event.frame);
    if (event.voice == 3) tripletFrames.push_back(event.frame);
  }
  assert((quarterFrames == std::vector<int64_t>{0, 24000}));
  assert((eighthFrames == std::vector<int64_t>{12000, 36000}));
  assert((sixteenthFrames == std::vector<int64_t>{6000, 18000, 30000, 42000}));
  assert((tripletFrames == std::vector<int64_t>{0, 8000, 16000, 24000, 32000, 40000}));
}

void eventsAtBlockBoundariesRemainPending() {
  auto settings = config();
  RhythmRenderer whole;
  whole.setSampleRate(48000);
  whole.begin(0, settings);
  std::vector<ScheduledEvent> expected;
  std::array<float, 12000> wholeOutput{};
  whole.render(wholeOutput.data(), static_cast<int32_t>(wholeOutput.size()), settings, collect, &expected);

  RhythmRenderer split;
  split.setSampleRate(48000);
  split.begin(0, settings);
  std::vector<ScheduledEvent> actual;
  std::array<float, 6000> firstOutput{};
  std::array<float, 6000> secondOutput{};
  split.render(firstOutput.data(), static_cast<int32_t>(firstOutput.size()), settings, collect, &actual);
  split.render(secondOutput.data(), static_cast<int32_t>(secondOutput.size()), settings, collect, &actual);

  assert(actual.size() == expected.size());
  for (size_t index = 0; index < actual.size(); ++index) {
    assert(actual[index].frame == expected[index].frame);
    assert(actual[index].voice == expected[index].voice);
    assert(actual[index].frequency == expected[index].frequency);
  }
  assert(std::any_of(actual.begin(), actual.end(), [](const ScheduledEvent& event) {
    return event.voice == 2 && event.frame == 6000;
  }));
}

void fractionalGridDoesNotAccumulateRounding() {
  constexpr int64_t origin = 37;
  constexpr double sampleRate = 44100.0;
  constexpr double period = sampleRate * 60.0 / 137.0;
  for (uint64_t ordinal = 0; ordinal <= 100000; ++ordinal) {
    const int64_t actual = RhythmRenderer::gridFrame(origin, ordinal, 1, period);
    const double ideal = static_cast<double>(origin) + ordinal * period;
    assert(std::abs(static_cast<double>(actual) - ideal) <= 0.500001);
  }

  const int64_t exactPosition = RhythmRenderer::gridFrame(origin, 100000, 1, period);
  const int64_t roundedStepPosition = origin + 100000 * static_cast<int64_t>(std::llround(period));
  assert(std::abs(exactPosition - roundedStepPosition) > 1000);
}

void tempoChangePreservesBeatPhase() {
  RhythmRenderer renderer;
  renderer.setSampleRate(48000);
  auto settings = config(120.0);
  renderer.begin(0, settings);
  std::vector<ScheduledEvent> events;
  std::array<float, 6000> firstBlock{};
  renderer.render(firstBlock.data(), static_cast<int32_t>(firstBlock.size()), settings, collect, &events);

  settings.bpm = 137.0;
  std::array<float, 18000> secondBlock{};
  renderer.render(secondBlock.data(), static_cast<int32_t>(secondBlock.size()), settings, collect, &events);
  const int64_t nextQuarter = 21767;
  bool found = false;
  for (const auto& event : events) {
    if (event.voice == 0 && event.frame == nextQuarter) found = true;
  }
  assert(found);
}

void pendingResetWaitsForPlay() {
  std::atomic<bool> resetRequested{true};
  assert(!rhythmwatch::takeResetIfPlaying(resetRequested, false));
  assert(resetRequested.load());
  assert(rhythmwatch::takeResetIfPlaying(resetRequested, true));
  assert(!resetRequested.load());
}

void stageStopsAfterEightBars() {
  RhythmRenderer renderer;
  renderer.setSampleRate(48000);
  auto settings = config(120.0, 1);
  renderer.begin(0, settings);
  std::vector<ScheduledEvent> events;
  constexpr int32_t frames = 768001;
  std::vector<float> output(frames);
  assert(renderer.render(output.data(), frames, settings, collect, &events));
  int quarterCount = 0;
  for (const auto& event : events) {
    if (event.voice == 0) ++quarterCount;
  }
  assert(quarterCount == 32);
}

void practiceAndStageKeepTheSameAccents() {
  constexpr int32_t frames = 768000;
  std::vector<float> output(frames);
  std::vector<ScheduledEvent> practiceEvents;
  std::vector<ScheduledEvent> stageEvents;
  auto practiceSettings = config(120.0, 0);
  auto stageSettings = config(120.0, 1);
  RhythmRenderer practice;
  RhythmRenderer stage;
  practice.setSampleRate(48000);
  stage.setSampleRate(48000);
  practice.begin(0, practiceSettings);
  stage.begin(0, stageSettings);
  assert(!practice.render(output.data(), frames, practiceSettings, collect, &practiceEvents));
  assert(!stage.render(output.data(), frames, stageSettings, collect, &stageEvents));

  assert(practiceEvents.size() == stageEvents.size());
  for (size_t index = 0; index < practiceEvents.size(); ++index) {
    const auto& practiceEvent = practiceEvents[index];
    const auto& stageEvent = stageEvents[index];
    assert(practiceEvent.frame == stageEvent.frame);
    assert(practiceEvent.voice == stageEvent.voice);
    assert(practiceEvent.frequency == stageEvent.frequency);
    assert(practiceEvent.gain == stageEvent.gain);
    assert(practiceEvent.timbre == stageEvent.timbre);
  }
}

void sessionModeIsFixedUntilTheNextStart() {
  constexpr int32_t frames = 768001;
  std::vector<float> output(frames);

  RhythmRenderer practice;
  practice.setSampleRate(48000);
  auto practiceSettings = config(120.0, 0);
  practice.begin(0, practiceSettings);
  practiceSettings.mode = 1;
  assert(!practice.render(output.data(), frames, practiceSettings));

  RhythmRenderer stage;
  stage.setSampleRate(48000);
  auto stageSettings = config(120.0, 1);
  stage.begin(0, stageSettings);
  stageSettings.mode = 0;
  assert(stage.render(output.data(), frames, stageSettings));
}
}

int main() {
  eventTimingAndSameVoiceEvents();
  eventsAtBlockBoundariesRemainPending();
  fractionalGridDoesNotAccumulateRounding();
  tempoChangePreservesBeatPhase();
  pendingResetWaitsForPlay();
  stageStopsAfterEightBars();
  practiceAndStageKeepTheSameAccents();
  sessionModeIsFixedUntilTheNextStart();
  std::cout << "rhythm renderer checks passed\n";
}
