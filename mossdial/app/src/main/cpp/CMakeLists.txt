cmake_minimum_required(VERSION 3.22.1)
project(mossdial_ai C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static llama.cpp/ggml, linked into one small JNI shared library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Prune the upstream build down to the inference library. See
# third_party/llama.cpp/PINNED_COMMIT.md: the vendored tree is missing the
# directories these options would otherwise build.
set(LLAMA_BUILD_IS_DEV    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE      OFF CACHE BOOL "" FORCE)

# ggml: CPU backend only. No native detection (the build is cross compiled),
# no OpenMP, no llamafile kernels, no accelerators.
set(GGML_CPU              ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_LTO              OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
set(GGML_BLAS             OFF CACHE BOOL "" FORCE)
set(GGML_RPC              OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)
set(GGML_METAL            OFF CACHE BOOL "" FORCE)
set(GGML_CUDA             OFF CACHE BOOL "" FORCE)
set(GGML_HIP              OFF CACHE BOOL "" FORCE)
set(GGML_SYCL             OFF CACHE BOOL "" FORCE)
set(GGML_MUSA             OFF CACHE BOOL "" FORCE)
set(GGML_CANN             OFF CACHE BOOL "" FORCE)
set(GGML_ET               OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL           OFF CACHE BOOL "" FORCE)
set(GGML_WEBGPU           OFF CACHE BOOL "" FORCE)
set(GGML_OPENVINO         OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON          OFF CACHE BOOL "" FORCE)
set(GGML_ZDNN             OFF CACHE BOOL "" FORCE)
set(GGML_ZENDNN           OFF CACHE BOOL "" FORCE)
set(GGML_VIRTGPU          OFF CACHE BOOL "" FORCE)
set(GGML_VIRTGPU_BACKEND  OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE       OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
set(GGML_CPU_HBM          OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/llama.cpp llama_build)

add_library(mossdial_ai SHARED
    mossdial_llama_jni.cpp
)

target_compile_options(mossdial_ai PRIVATE
    -Wall
    -Wextra
    -Werror=format-security
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
)

target_link_libraries(mossdial_ai PRIVATE llama log android)
