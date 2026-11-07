cmake_minimum_required(VERSION 3.22.1)
project("needle_jni")

# 100% 純原創自研 C++17 三大異質微引擎（Micro-Llama GGUF Q4 + MiniSat/PDDL 規劃器 + TRM Policy/MCTS）與向量推論引擎
add_library(needle_jni SHARED
    self_vector_engine.cpp
    sat_smt_pddl_planner.cpp
    trm_policy_mcts.cpp
    micro_cot_gguf_engine.cpp
    needle_jni.cpp
    cute_girl_vocoder.cpp
    zero_copy_vision_accel.cpp
)

target_include_directories(needle_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(needle_jni
    ${log-lib}
    ${android-lib}
    m
    dl
)
