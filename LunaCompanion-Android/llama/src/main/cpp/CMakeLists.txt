cmake_minimum_required(VERSION 3.22.1)
project("llama-android")

# Point LLAMA_CPP_DIR to your cloned/vendored llama.cpp repository root
# (e.g., third_party/llama.cpp inside the project root)
if (NOT DEFINED LLAMA_CPP_DIR)
    set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
endif()

include(FetchContent)

if (EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(STATUS "Using local llama.cpp source at ${LLAMA_CPP_DIR}")
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE)
    add_subdirectory("${LLAMA_CPP_DIR}" llama_cpp_build)
else()
    message(STATUS "Fetching official llama.cpp repository for Android NDK build")
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        master
        GIT_SHALLOW    TRUE
    )
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(
    llama-android
    SHARED
    llama-android.cpp
)

find_library(LOG_LIB log)

target_link_libraries(
    llama-android
    llama
    common
    ${LOG_LIB}
    android
)
