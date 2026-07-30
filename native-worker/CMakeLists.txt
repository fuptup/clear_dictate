cmake_minimum_required(VERSION 3.24)

project(ClearDictateNativeWorker LANGUAGES CXX)

add_library(
    clear_dictate_text_generation_policy
    src/TextGenerationPolicy.cpp
)

target_include_directories(
    clear_dictate_text_generation_policy
    PUBLIC
    include
)
target_compile_features(clear_dictate_text_generation_policy PUBLIC cxx_std_17)
set_target_properties(clear_dictate_text_generation_policy PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_text_generation_policy PRIVATE /EHsc /W4)
endif()

add_executable(clear_dictate_native_policy_tests tests/TextGenerationPolicyTests.cpp)
target_link_libraries(clear_dictate_native_policy_tests PRIVATE clear_dictate_text_generation_policy)
target_compile_features(clear_dictate_native_policy_tests PRIVATE cxx_std_17)
set_target_properties(clear_dictate_native_policy_tests PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_native_policy_tests PRIVATE /EHsc /W4)
endif()

enable_testing()
add_test(NAME clear_dictate_native_policy_tests COMMAND clear_dictate_native_policy_tests)

option(CLEAR_DICTATE_ENABLE_LLAMA "Build the project-owned llama.cpp text-generation adapter." OFF)

if(CLEAR_DICTATE_ENABLE_LLAMA)
    if(NOT DEFINED LLAMA_CPP_SOURCE_DIR)
        message(FATAL_ERROR "LLAMA_CPP_SOURCE_DIR must identify the pinned llama.cpp source checkout.")
    endif()

    include(dependencies/llama-lock.cmake)
    find_package(Git REQUIRED)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" -C "${LLAMA_CPP_SOURCE_DIR}" rev-parse HEAD
        RESULT_VARIABLE LLAMA_COMMIT_RESULT
        OUTPUT_VARIABLE LLAMA_ACTUAL_COMMIT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )

    if(NOT LLAMA_COMMIT_RESULT EQUAL 0 OR NOT LLAMA_ACTUAL_COMMIT STREQUAL CLEAR_DICTATE_LLAMA_COMMIT)
        message(FATAL_ERROR "llama.cpp must be the locked ClearDictate revision ${CLEAR_DICTATE_LLAMA_COMMIT}.")
    endif()

    execute_process(
        COMMAND "${GIT_EXECUTABLE}" -C "${LLAMA_CPP_SOURCE_DIR}" status --porcelain --untracked-files=no
        RESULT_VARIABLE LLAMA_STATUS_RESULT
        OUTPUT_VARIABLE LLAMA_DIRTY_OUTPUT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )

    if(NOT LLAMA_STATUS_RESULT EQUAL 0 OR NOT LLAMA_DIRTY_OUTPUT STREQUAL "")
        message(FATAL_ERROR "The locked llama.cpp checkout must have no tracked modifications.")
    endif()

    set(BUILD_SHARED_LIBS OFF)
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "" FORCE)
    set(GGML_CUDA OFF CACHE BOOL "" FORCE)
    set(GGML_ET OFF CACHE BOOL "" FORCE)
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
    set(GGML_HIP OFF CACHE BOOL "" FORCE)
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
    set(GGML_MUSA OFF CACHE BOOL "" FORCE)
    # A production worker must run on supported x64 computers that lack the build
    # machine's newest instruction set. Runtime-specific optimization can be added
    # later through separately packaged, capability-checked backends.
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    set(GGML_OPENVINO OFF CACHE BOOL "" FORCE)
    set(GGML_RPC OFF CACHE BOOL "" FORCE)
    set(GGML_SYCL OFF CACHE BOOL "" FORCE)
    set(GGML_VIRTGPU OFF CACHE BOOL "" FORCE)
    set(GGML_VIRTGPU_BACKEND OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_WEBGPU OFF CACHE BOOL "" FORCE)
    set(GGML_ZDNN OFF CACHE BOOL "" FORCE)
    set(GGML_ZENDNN OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)

    add_subdirectory("${LLAMA_CPP_SOURCE_DIR}" llama.cpp EXCLUDE_FROM_ALL)

    add_library(clear_dictate_llama_text_engine src/LlamaTextEngine.cpp)
    target_include_directories(clear_dictate_llama_text_engine PUBLIC include)
    target_link_libraries(clear_dictate_llama_text_engine PUBLIC clear_dictate_text_generation_policy PRIVATE llama)
    target_compile_features(clear_dictate_llama_text_engine PUBLIC cxx_std_17)
    set_target_properties(clear_dictate_llama_text_engine PROPERTIES CXX_EXTENSIONS OFF)

    if(MSVC)
        target_compile_options(clear_dictate_llama_text_engine PRIVATE /EHsc /W4)
    endif()

    option(CLEAR_DICTATE_ENABLE_MODEL_INTEGRATION_TESTS "Build tests that load the pinned local Qwen model." OFF)

    if(CLEAR_DICTATE_ENABLE_MODEL_INTEGRATION_TESTS)
        if(NOT DEFINED CLEAR_DICTATE_TEST_TEXT_MODEL OR CLEAR_DICTATE_TEST_TEXT_MODEL STREQUAL "")
            message(FATAL_ERROR "CLEAR_DICTATE_TEST_TEXT_MODEL must identify the pinned Qwen model when integration tests are enabled.")
        endif()

        add_executable(clear_dictate_llama_text_engine_tests tests/LlamaTextEngineTests.cpp)
        target_link_libraries(clear_dictate_llama_text_engine_tests PRIVATE clear_dictate_llama_text_engine)
        target_compile_features(clear_dictate_llama_text_engine_tests PRIVATE cxx_std_17)
        set_target_properties(clear_dictate_llama_text_engine_tests PROPERTIES CXX_EXTENSIONS OFF)

        if(MSVC)
            target_compile_options(clear_dictate_llama_text_engine_tests PRIVATE /EHsc /W4)
        endif()

        add_test(
            NAME clear_dictate_llama_text_engine_tests
            COMMAND clear_dictate_llama_text_engine_tests "${CLEAR_DICTATE_TEST_TEXT_MODEL}"
        )
    endif()
endif()
