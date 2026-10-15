# Host (Linux) build of the native OpenVPN 3 side, used for testing without
# an Android device:
#   ovpn_hosttest  - runs chain::Client against a real server and hands the
#                    packet socket to a child process (see run-e2e.sh)
#   ovpn3jni_host  - compile/link check of the JNI bridge against the JDK
cmake_minimum_required(VERSION 3.22.1)
project(ovpn_hosttest C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
include(${NATIVE_DIR}/cmake/openvpn3.cmake)
find_package(Threads REQUIRED)

add_executable(ovpn_hosttest ovpn_hosttest.cpp)
add_openvpn3_core(ovpn_hosttest)
# Use the Android/iOS TunBuilderBase code path on Linux too.
target_compile_definitions(ovpn_hosttest PRIVATE USE_TUN_BUILDER)
target_include_directories(ovpn_hosttest PRIVATE ${NATIVE_DIR})
target_link_libraries(ovpn_hosttest PRIVATE Threads::Threads)

find_package(JNI)
if(JNI_FOUND)
    add_library(ovpn3jni_host SHARED ${NATIVE_DIR}/ovpn3_jni.cpp)
    add_openvpn3_core(ovpn3jni_host)
    target_compile_definitions(ovpn3jni_host PRIVATE USE_TUN_BUILDER)
    target_include_directories(ovpn3jni_host PRIVATE ${NATIVE_DIR} ${JNI_INCLUDE_DIRS}
        ${CMAKE_CURRENT_SOURCE_DIR}/stub)
    target_link_options(ovpn3jni_host PRIVATE -Wl,--no-undefined)
    target_link_libraries(ovpn3jni_host PRIVATE Threads::Threads)
endif()
