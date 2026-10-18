cmake_minimum_required(VERSION 3.22.1)
project(pulse200_native_tests LANGUAGES CXX)
enable_testing()
add_executable(rhythm_renderer_test
  ../../app/src/main/cpp/rhythm_renderer.cpp
  rhythm_renderer_test.cpp
)
target_compile_features(rhythm_renderer_test PRIVATE cxx_std_20)
add_test(NAME rhythm_renderer_test COMMAND rhythm_renderer_test)
