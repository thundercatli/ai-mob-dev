cmake_minimum_required(VERSION 3.22.1)

project(aidevmob_frpc_core LANGUAGES CXX)

set(FRPC_CORE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../native/frpc_core")

add_subdirectory("${FRPC_CORE_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/frpc_core")

add_library(frpc_core SHARED frpc_jni.cpp)
target_link_libraries(frpc_core PRIVATE aidevmob_frpc_core log)
