cmake_minimum_required(VERSION 3.22.1)
project(ovpn3jni C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(cmake/openvpn3.cmake)

add_library(ovpn3jni SHARED ovpn3_jni.cpp)
add_openvpn3_core(ovpn3jni)
target_include_directories(ovpn3jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(ovpn3jni PRIVATE log)
