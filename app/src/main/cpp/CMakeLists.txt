cmake_minimum_required(VERSION 3.22)
project(watranscriber)

set(CMAKE_CXX_STANDARD 17)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp whisper_build)

set(NEEDLE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/needle/android-arm64)
add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES
    IMPORTED_LOCATION ${NEEDLE_DIR}/libneedle.a
    INTERFACE_INCLUDE_DIRECTORIES ${NEEDLE_DIR})

add_library(watranscriber SHARED whisper_jni.cpp needle_jni.cpp)

target_link_libraries(watranscriber PRIVATE whisper needle log android)
