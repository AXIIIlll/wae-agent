cmake_minimum_required(VERSION 3.22.1)
project("wae_jni" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    wae_jni
    SHARED
    wae_jni.cpp
)

target_compile_options(
    wae_jni
    PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wextra
)

target_link_options(
    wae_jni
    PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    -s
)

find_library(log-lib log)
find_library(android-lib android)
find_library(nativewindow-lib nativewindow)

target_link_libraries(
    wae_jni
    ${log-lib}
    ${android-lib}
    ${nativewindow-lib}
)
