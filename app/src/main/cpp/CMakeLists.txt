cmake_minimum_required(VERSION 3.22.1)
project(autocropjpeg LANGUAGES C CXX)

# libjpeg-turbo 3.2.0 static libraries need PIC when linked into our Android shared JNI library.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG ON CACHE BOOL "" FORCE)
set(WITH_TOOLS OFF CACHE BOOL "" FORCE)
set(WITH_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_JAVA OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    jpeg_turbo
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz
    URL_HASH SHA256=6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e
)
FetchContent_MakeAvailable(jpeg_turbo)

add_library(autocropjpeg SHARED native_crop.cpp)
target_include_directories(autocropjpeg PRIVATE
    ${jpeg_turbo_SOURCE_DIR}/src
    ${jpeg_turbo_BINARY_DIR}
)
target_link_libraries(autocropjpeg PRIVATE turbojpeg-static log)
