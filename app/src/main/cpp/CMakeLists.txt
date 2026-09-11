cmake_minimum_required(VERSION 3.22.1)
project(autocropjpeg LANGUAGES C CXX)

# libjpeg-turbo 3.2.x intentionally refuses add_subdirectory()/FetchContent
# integration. Build and install it as an independent Android CMake project,
# then link our JNI bridge against the resulting static TurboJPEG library.
include(ExternalProject)

set(JPEG_TURBO_PREFIX "${CMAKE_BINARY_DIR}/jpeg-turbo")
set(JPEG_TURBO_INSTALL "${JPEG_TURBO_PREFIX}/install")
set(JPEG_TURBO_LIB "${JPEG_TURBO_INSTALL}/lib/libturbojpeg.a")

# CMake validates imported include directories during configure, before the
# external project has been built, so create the future install directory now.
file(MAKE_DIRECTORY "${JPEG_TURBO_INSTALL}/include")

ExternalProject_Add(
    jpeg_turbo_ep
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz
    URL_HASH SHA256=6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e
    PREFIX "${JPEG_TURBO_PREFIX}"
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_INSTALL_PREFIX=${JPEG_TURBO_INSTALL}
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=ON
        -DWITH_TOOLS=OFF
        -DWITH_TESTS=OFF
        -DWITH_JAVA=OFF
    BUILD_BYPRODUCTS "${JPEG_TURBO_LIB}"
)

add_library(turbojpeg_external STATIC IMPORTED GLOBAL)
set_target_properties(
    turbojpeg_external
    PROPERTIES
        IMPORTED_LOCATION "${JPEG_TURBO_LIB}"
        INTERFACE_INCLUDE_DIRECTORIES "${JPEG_TURBO_INSTALL}/include"
)
add_dependencies(turbojpeg_external jpeg_turbo_ep)

add_library(autocropjpeg SHARED native_crop.cpp)
add_dependencies(autocropjpeg jpeg_turbo_ep)
target_include_directories(autocropjpeg PRIVATE "${JPEG_TURBO_INSTALL}/include")
target_link_libraries(autocropjpeg PRIVATE turbojpeg_external log)
