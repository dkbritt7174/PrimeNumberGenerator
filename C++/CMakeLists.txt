cmake_minimum_required(VERSION 3.15)
project(PrimeSieve LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Cache-level tuning: 1 = L1 (~16 KB), 2 = L2 (~256 KB) [default], 3 = L3 (~4 MB)
set(CACHE_LEVEL "2" CACHE STRING "Sieve segment cache level (1, 2, or 3)")

add_executable(PrimeSieve PrimeSieve.cpp)

target_compile_definitions(PrimeSieve PRIVATE CACHE_LEVEL=${CACHE_LEVEL})

target_compile_options(PrimeSieve PRIVATE
    # MSVC
    $<$<CXX_COMPILER_ID:MSVC>:
        /O2
        /arch:AVX2
        /W3
        /fp:fast
    >
    # GCC / Clang
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:
        -O3
        -mavx2
        -march=native
        -Wall
        -Wextra
        -ffast-math
    >
)
