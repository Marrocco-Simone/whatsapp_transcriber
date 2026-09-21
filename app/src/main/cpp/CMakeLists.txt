cmake_minimum_required(VERSION 3.22)
project(watranscriber)

set(CMAKE_CXX_STANDARD 17)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp whisper_build)

add_library(watranscriber SHARED whisper_jni.cpp)

target_link_libraries(watranscriber PRIVATE whisper log android)
