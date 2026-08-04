cmake_minimum_required(VERSION 3.22.1)

project(aidevmob_frpc_core LANGUAGES CXX)

add_library(aidevmob_frpc_core STATIC
    src/frpc_core.cpp
    src/frpc_crypto.cpp
    src/frpc_streams.cpp
)
target_include_directories(aidevmob_frpc_core PUBLIC include)
target_compile_features(aidevmob_frpc_core PUBLIC cxx_std_17)

set_target_properties(aidevmob_frpc_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

option(AIDEVMOB_FRPC_BUILD_DRIVER "Build the portable STCP test driver" OFF)
if(AIDEVMOB_FRPC_BUILD_DRIVER)
    add_executable(aidevmob_frpc_driver tools/stcp_driver.cpp)
    target_link_libraries(aidevmob_frpc_driver PRIVATE aidevmob_frpc_core)
    find_package(OpenSSL QUIET)
    if(OpenSSL_FOUND)
        target_compile_definitions(aidevmob_frpc_driver PRIVATE AIDEVMOB_FRPC_DRIVER_OPENSSL=1)
        target_link_libraries(aidevmob_frpc_driver PRIVATE OpenSSL::SSL OpenSSL::Crypto)
    endif()
endif()

option(AIDEVMOB_FRPC_BUILD_TESTS "Build portable FRPC core tests" OFF)
if(AIDEVMOB_FRPC_BUILD_TESTS)
    enable_testing()
    add_executable(aidevmob_frpc_crypto_test tests/frpc_crypto_test.cpp)
    target_include_directories(aidevmob_frpc_crypto_test PRIVATE src)
    target_link_libraries(aidevmob_frpc_crypto_test PRIVATE aidevmob_frpc_core)
    add_test(NAME aidevmob_frpc_crypto_test COMMAND aidevmob_frpc_crypto_test)

    add_executable(aidevmob_frpc_streams_test tests/frpc_streams_test.cpp)
    target_include_directories(aidevmob_frpc_streams_test PRIVATE src)
    target_link_libraries(aidevmob_frpc_streams_test PRIVATE aidevmob_frpc_core)
    add_test(NAME aidevmob_frpc_streams_test COMMAND aidevmob_frpc_streams_test)
endif()
