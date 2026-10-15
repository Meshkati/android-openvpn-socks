# Fetches OpenVPN 3 core and its dependencies (asio, mbed TLS, lz4, fmt) and
# provides add_openvpn3_core(<target>), which compiles the core into <target>.
# Shared by the Android build and the host test in /hosttest.

include(FetchContent)

set(OPENVPN3_TAG release/3.11.7)
set(ASIO_TAG asio-1-24-0)   # version the openvpn3 release pins in deps/lib-versions
set(MBEDTLS_TAG v3.6.7)
set(LZ4_TAG v1.10.0)
set(FMT_TAG 12.2.0)

# SOURCE_SUBDIR points at a directory without a CMakeLists.txt, so these are
# only downloaded, not added as sub-projects.
FetchContent_Declare(openvpn3
    GIT_REPOSITORY https://github.com/OpenVPN/openvpn3.git
    GIT_TAG ${OPENVPN3_TAG} GIT_SHALLOW TRUE SOURCE_SUBDIR none UPDATE_DISCONNECTED TRUE
    PATCH_COMMAND ${CMAKE_COMMAND}
        -DPATCH_GLOB=${CMAKE_CURRENT_LIST_DIR}/../patches/openvpn3-*.patch
        -P ${CMAKE_CURRENT_LIST_DIR}/apply_patches.cmake)
FetchContent_Declare(lz4
    GIT_REPOSITORY https://github.com/lz4/lz4.git
    GIT_TAG ${LZ4_TAG} GIT_SHALLOW TRUE SOURCE_SUBDIR none)
FetchContent_MakeAvailable(openvpn3 lz4)

# The openvpn3 release needs its patches on top of asio.
FetchContent_Declare(asio
    GIT_REPOSITORY https://github.com/chriskohlhoff/asio.git
    GIT_TAG ${ASIO_TAG} GIT_SHALLOW TRUE SOURCE_SUBDIR none UPDATE_DISCONNECTED TRUE
    PATCH_COMMAND ${CMAKE_COMMAND}
        -DPATCH_GLOB=${openvpn3_SOURCE_DIR}/deps/asio/patches/*.patch
        -P ${CMAKE_CURRENT_LIST_DIR}/apply_patches.cmake)
FetchContent_MakeAvailable(asio)

set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
FetchContent_Declare(mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG ${MBEDTLS_TAG} GIT_SHALLOW TRUE)

set(FMT_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(fmt
    GIT_REPOSITORY https://github.com/fmtlib/fmt.git
    GIT_TAG ${FMT_TAG} GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(mbedtls fmt)

add_library(ovpn_lz4 STATIC ${lz4_SOURCE_DIR}/lib/lz4.c)
target_include_directories(ovpn_lz4 PUBLIC ${lz4_SOURCE_DIR}/lib)
set_target_properties(ovpn_lz4 PROPERTIES POSITION_INDEPENDENT_CODE ON)
foreach(t mbedtls mbedx509 mbedcrypto fmt)
    set_target_properties(${t} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endforeach()

function(add_openvpn3_core target)
    target_sources(${target} PRIVATE
        ${openvpn3_SOURCE_DIR}/client/ovpncli.cpp
        ${openvpn3_SOURCE_DIR}/openvpn/crypto/data_epoch.cpp)
    # asio first: openvpn3 relies on the patched copy.
    target_include_directories(${target} PRIVATE
        ${asio_SOURCE_DIR}/asio/include
        ${openvpn3_SOURCE_DIR}
        ${openvpn3_SOURCE_DIR}/client)
    target_compile_definitions(${target} PRIVATE
        ASIO_STANDALONE
        USE_ASIO
        USE_MBEDTLS
        HAVE_LZ4
        OPENVPN_CORE_GIT_VERSION="${OPENVPN3_TAG}")
    target_link_libraries(${target} PRIVATE mbedtls mbedx509 mbedcrypto ovpn_lz4 fmt::fmt)
endfunction()
