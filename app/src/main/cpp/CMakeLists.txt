cmake_minimum_required(VERSION 3.22.1)
project(rhythmwatch LANGUAGES CXX)
include(FetchContent)
set(OBOE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../oboe")
if(EXISTS "${OBOE_DIR}/CMakeLists.txt")
  add_subdirectory("${OBOE_DIR}" oboe)
else()
  FetchContent_Declare(oboe GIT_REPOSITORY https://github.com/google/oboe.git GIT_TAG 1.9.3)
  FetchContent_MakeAvailable(oboe)
endif()
add_library(rhythmwatch SHARED rhythm_engine.cpp)
target_link_libraries(rhythmwatch PRIVATE oboe)
target_compile_features(rhythmwatch PRIVATE cxx_std_20)
target_link_options(rhythmwatch PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
)
