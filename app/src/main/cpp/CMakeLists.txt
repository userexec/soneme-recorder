cmake_minimum_required(VERSION 3.22.1)
project(soneme_lame LANGUAGES C CXX)

include(FetchContent)
# Android-ready LAME 3.100 libmp3lame sources. This repository carries the small
# portability edits needed by the NDK; Soneme Recorder supplies its own JNI bridge.
FetchContent_Declare(
    lame_android
    GIT_REPOSITORY https://github.com/KosmoSakura/AudioHertzDecoder.git
    GIT_TAG 43b07de55d9e9de7edc3ddb2bf3e9464a651fae4
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(lame_android)
if(NOT lame_android_POPULATED)
    FetchContent_Populate(lame_android)
endif()

set(LAME_DIR "${lame_android_SOURCE_DIR}/app/src/main/cpp/lamemp3")
file(GLOB LAME_SOURCES CONFIGURE_DEPENDS "${LAME_DIR}/*.c")

add_library(soneme_lame SHARED lame_jni.cpp ${LAME_SOURCES})
target_include_directories(soneme_lame PRIVATE "${LAME_DIR}")
target_compile_definitions(soneme_lame PRIVATE STDC_HEADERS=1)
find_library(log-lib log)
target_link_libraries(soneme_lame PRIVATE ${log-lib} m)
