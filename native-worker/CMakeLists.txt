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

add_library(clear_dictate_worker_protocol src/WorkerProtocol.cpp)
target_include_directories(clear_dictate_worker_protocol PUBLIC include)
target_compile_features(clear_dictate_worker_protocol PUBLIC cxx_std_17)
set_target_properties(clear_dictate_worker_protocol PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_worker_protocol PRIVATE /EHsc /W4)
endif()

add_executable(clear_dictate_worker_protocol_tests tests/WorkerProtocolTests.cpp)
target_link_libraries(clear_dictate_worker_protocol_tests PRIVATE clear_dictate_worker_protocol)
target_compile_features(clear_dictate_worker_protocol_tests PRIVATE cxx_std_17)
set_target_properties(clear_dictate_worker_protocol_tests PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_worker_protocol_tests PRIVATE /EHsc /W4)
endif()

add_test(NAME clear_dictate_worker_protocol_tests COMMAND clear_dictate_worker_protocol_tests)

add_library(clear_dictate_worker_payloads src/WorkerPayloads.cpp)
target_include_directories(clear_dictate_worker_payloads PUBLIC include)
target_compile_features(clear_dictate_worker_payloads PUBLIC cxx_std_17)
set_target_properties(clear_dictate_worker_payloads PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_worker_payloads PRIVATE /EHsc /W4)
endif()

add_executable(clear_dictate_worker_payload_tests tests/WorkerPayloadTests.cpp)
target_link_libraries(clear_dictate_worker_payload_tests PRIVATE clear_dictate_worker_payloads)
target_compile_features(clear_dictate_worker_payload_tests PRIVATE cxx_std_17)
set_target_properties(clear_dictate_worker_payload_tests PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_worker_payload_tests PRIVATE /EHsc /W4)
endif()

add_test(NAME clear_dictate_worker_payload_tests COMMAND clear_dictate_worker_payload_tests)

add_library(clear_dictate_worker_transcript_payloads src/WorkerTranscriptPayloads.cpp)
target_include_directories(clear_dictate_worker_transcript_payloads PUBLIC include)
target_compile_features(clear_dictate_worker_transcript_payloads PUBLIC cxx_std_17)
set_target_properties(clear_dictate_worker_transcript_payloads PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_worker_transcript_payloads PRIVATE /EHsc /W4)
endif()

add_executable(clear_dictate_worker_transcript_payload_tests tests/WorkerTranscriptPayloadTests.cpp)
target_link_libraries(clear_dictate_worker_transcript_payload_tests PRIVATE clear_dictate_worker_transcript_payloads)
target_compile_features(clear_dictate_worker_transcript_payload_tests PRIVATE cxx_std_17)
set_target_properties(clear_dictate_worker_transcript_payload_tests PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_worker_transcript_payload_tests PRIVATE /EHsc /W4)
endif()

add_test(NAME clear_dictate_worker_transcript_payload_tests COMMAND clear_dictate_worker_transcript_payload_tests)

add_library(clear_dictate_bounded_audio_block_queue src/BoundedAudioBlockQueue.cpp)
target_include_directories(clear_dictate_bounded_audio_block_queue PUBLIC include)
target_compile_features(clear_dictate_bounded_audio_block_queue PUBLIC cxx_std_17)
set_target_properties(clear_dictate_bounded_audio_block_queue PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    # C4324 reports the intentional cache-line padding around the two indices.
    target_compile_options(clear_dictate_bounded_audio_block_queue PRIVATE /EHsc /W4 /wd4324)
endif()

add_executable(clear_dictate_bounded_audio_block_queue_tests tests/BoundedAudioBlockQueueTests.cpp)
target_link_libraries(clear_dictate_bounded_audio_block_queue_tests PRIVATE clear_dictate_bounded_audio_block_queue)
target_compile_features(clear_dictate_bounded_audio_block_queue_tests PRIVATE cxx_std_17)
set_target_properties(clear_dictate_bounded_audio_block_queue_tests PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_bounded_audio_block_queue_tests PRIVATE /EHsc /W4 /wd4324)
endif()

add_test(NAME clear_dictate_bounded_audio_block_queue_tests COMMAND clear_dictate_bounded_audio_block_queue_tests)

add_library(clear_dictate_audio_capture_packet_processor src/AudioCapturePacketProcessor.cpp)
target_include_directories(clear_dictate_audio_capture_packet_processor PUBLIC include)
target_link_libraries(clear_dictate_audio_capture_packet_processor PUBLIC clear_dictate_bounded_audio_block_queue)
target_compile_features(clear_dictate_audio_capture_packet_processor PUBLIC cxx_std_17)
set_target_properties(clear_dictate_audio_capture_packet_processor PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_audio_capture_packet_processor PRIVATE /EHsc /W4 /wd4324)
endif()

add_executable(clear_dictate_audio_capture_packet_processor_tests tests/AudioCapturePacketProcessorTests.cpp)
target_link_libraries(clear_dictate_audio_capture_packet_processor_tests PRIVATE clear_dictate_audio_capture_packet_processor)
target_compile_features(clear_dictate_audio_capture_packet_processor_tests PRIVATE cxx_std_17)
set_target_properties(clear_dictate_audio_capture_packet_processor_tests PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_audio_capture_packet_processor_tests PRIVATE /EHsc /W4 /wd4324)
endif()

add_test(NAME clear_dictate_audio_capture_packet_processor_tests COMMAND clear_dictate_audio_capture_packet_processor_tests)

add_library(clear_dictate_text_inference_worker_session src/TextInferenceWorkerSession.cpp)
target_include_directories(clear_dictate_text_inference_worker_session PUBLIC include)
target_link_libraries(
    clear_dictate_text_inference_worker_session
    PUBLIC
    clear_dictate_worker_payloads
    clear_dictate_worker_protocol
)
target_compile_features(clear_dictate_text_inference_worker_session PUBLIC cxx_std_17)
set_target_properties(clear_dictate_text_inference_worker_session PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_text_inference_worker_session PRIVATE /EHsc /W4)
endif()

add_executable(clear_dictate_text_inference_worker_session_tests tests/TextInferenceWorkerSessionTests.cpp)
target_link_libraries(clear_dictate_text_inference_worker_session_tests PRIVATE clear_dictate_text_inference_worker_session)
target_compile_features(clear_dictate_text_inference_worker_session_tests PRIVATE cxx_std_17)
set_target_properties(clear_dictate_text_inference_worker_session_tests PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(clear_dictate_text_inference_worker_session_tests PRIVATE /EHsc /W4)
endif()

add_test(NAME clear_dictate_text_inference_worker_session_tests COMMAND clear_dictate_text_inference_worker_session_tests)

if(WIN32)
    add_library(clear_dictate_windows_audio_session_capture src/WindowsAudioSessionCapture.cpp)
    target_include_directories(clear_dictate_windows_audio_session_capture PUBLIC include)
    target_link_libraries(
        clear_dictate_windows_audio_session_capture
        PUBLIC
        clear_dictate_audio_capture_packet_processor
        PRIVATE
        avrt
        ole32
    )
    target_compile_features(clear_dictate_windows_audio_session_capture PUBLIC cxx_std_17)
    set_target_properties(clear_dictate_windows_audio_session_capture PROPERTIES CXX_EXTENSIONS OFF)

    if(MSVC)
        target_compile_options(clear_dictate_windows_audio_session_capture PRIVATE /EHsc /W4 /wd4324)
    endif()

    add_executable(clear_dictate_windows_audio_session_capture_smoke tests/WindowsAudioSessionCaptureSmoke.cpp)
    target_link_libraries(clear_dictate_windows_audio_session_capture_smoke PRIVATE clear_dictate_windows_audio_session_capture)
    target_compile_features(clear_dictate_windows_audio_session_capture_smoke PRIVATE cxx_std_17)
    set_target_properties(clear_dictate_windows_audio_session_capture_smoke PROPERTIES CXX_EXTENSIONS OFF)

    if(MSVC)
        target_compile_options(clear_dictate_windows_audio_session_capture_smoke PRIVATE /EHsc /W4 /wd4324)
    endif()

    add_executable(clear_dictate_windows_audio_session_capture_lifecycle_tests tests/WindowsAudioSessionCaptureLifecycleTests.cpp)
    target_link_libraries(clear_dictate_windows_audio_session_capture_lifecycle_tests PRIVATE clear_dictate_windows_audio_session_capture)
    target_compile_features(clear_dictate_windows_audio_session_capture_lifecycle_tests PRIVATE cxx_std_17)
    set_target_properties(clear_dictate_windows_audio_session_capture_lifecycle_tests PROPERTIES CXX_EXTENSIONS OFF)

    if(MSVC)
        target_compile_options(clear_dictate_windows_audio_session_capture_lifecycle_tests PRIVATE /EHsc /W4 /wd4324)
    endif()

    add_test(NAME clear_dictate_windows_audio_session_capture_lifecycle_tests COMMAND clear_dictate_windows_audio_session_capture_lifecycle_tests)

    add_executable(clear_dictate_worker_launcher src/WorkerLauncherMain.cpp)
    target_compile_features(clear_dictate_worker_launcher PRIVATE cxx_std_17)
    set_target_properties(clear_dictate_worker_launcher PROPERTIES CXX_EXTENSIONS OFF)

    if(MSVC)
        target_compile_options(clear_dictate_worker_launcher PRIVATE /EHsc /W4)
    endif()

    add_library(clear_dictate_verified_model_file src/VerifiedModelFile.cpp)
    target_include_directories(clear_dictate_verified_model_file PUBLIC include)
    target_compile_features(clear_dictate_verified_model_file PUBLIC cxx_std_17)
    target_link_libraries(clear_dictate_verified_model_file PRIVATE bcrypt)
    set_target_properties(clear_dictate_verified_model_file PROPERTIES CXX_EXTENSIONS OFF)

    if(MSVC)
        target_compile_options(clear_dictate_verified_model_file PRIVATE /EHsc /W4)
    endif()

    add_executable(clear_dictate_verified_model_file_tests tests/VerifiedModelFileTests.cpp)
    target_link_libraries(clear_dictate_verified_model_file_tests PRIVATE clear_dictate_verified_model_file)
    target_compile_features(clear_dictate_verified_model_file_tests PRIVATE cxx_std_17)
    set_target_properties(clear_dictate_verified_model_file_tests PROPERTIES CXX_EXTENSIONS OFF)

    if(MSVC)
        target_compile_options(clear_dictate_verified_model_file_tests PRIVATE /EHsc /W4)
    endif()

    add_test(NAME clear_dictate_verified_model_file_tests COMMAND clear_dictate_verified_model_file_tests)
endif()

option(CLEAR_DICTATE_ENABLE_MOONSHINE "Build the project-owned Moonshine speech-recognition adapter." OFF)

if(CLEAR_DICTATE_ENABLE_MOONSHINE)
    if(NOT WIN32 OR NOT MSVC OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8 OR NOT CMAKE_GENERATOR MATCHES "^Visual Studio 17 2022")
        message(FATAL_ERROR "The current ClearDictate Moonshine adapter requires Visual Studio 2022 targeting 64-bit Windows.")
    endif()
    if(NOT DEFINED MOONSHINE_SOURCE_DIR OR NOT DEFINED MOONSHINE_BUILD_DIR)
        message(FATAL_ERROR "MOONSHINE_SOURCE_DIR and MOONSHINE_BUILD_DIR must identify the pinned source and Debug build.")
    endif()

    include(dependencies/moonshine-lock.cmake)
    find_package(Git REQUIRED)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" -C "${MOONSHINE_SOURCE_DIR}" rev-parse HEAD
        RESULT_VARIABLE MOONSHINE_COMMIT_RESULT
        OUTPUT_VARIABLE MOONSHINE_ACTUAL_COMMIT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
    if(NOT MOONSHINE_COMMIT_RESULT EQUAL 0 OR NOT MOONSHINE_ACTUAL_COMMIT STREQUAL CLEAR_DICTATE_MOONSHINE_COMMIT)
        message(FATAL_ERROR "Moonshine must be the locked ClearDictate revision ${CLEAR_DICTATE_MOONSHINE_COMMIT}.")
    endif()

    execute_process(
        COMMAND "${GIT_EXECUTABLE}" -C "${MOONSHINE_SOURCE_DIR}" status --porcelain
        RESULT_VARIABLE MOONSHINE_STATUS_RESULT
        OUTPUT_VARIABLE MOONSHINE_DIRTY_OUTPUT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
    if(NOT MOONSHINE_STATUS_RESULT EQUAL 0 OR NOT MOONSHINE_DIRTY_OUTPUT STREQUAL "")
        message(FATAL_ERROR "The locked Moonshine checkout must have no tracked modifications.")
    endif()

    set(CLEAR_DICTATE_MOONSHINE_LIBRARY "${MOONSHINE_BUILD_DIR}/core/Debug/moonshine.lib")
    set(CLEAR_DICTATE_BIN_TOKENIZER_LIBRARY "${MOONSHINE_SOURCE_DIR}/core/bin-tokenizer/build/Debug/bin-tokenizer.lib")
    set(CLEAR_DICTATE_MOONSHINE_UTILS_LIBRARY "${MOONSHINE_SOURCE_DIR}/core/moonshine-utils/build/Debug/moonshine-utils.lib")
    set(CLEAR_DICTATE_ORT_UTILS_LIBRARY "${MOONSHINE_SOURCE_DIR}/core/ort-utils/build/Debug/ort-utils.lib")
    set(CLEAR_DICTATE_ONNX_RUNTIME_DIRECTORY "${MOONSHINE_SOURCE_DIR}/core/third-party/onnxruntime/lib/windows/x86_64")
    set(CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY "${CLEAR_DICTATE_ONNX_RUNTIME_DIRECTORY}/onnxruntime.lib")
    set(CLEAR_DICTATE_ONNX_RUNTIME_DLL "${CLEAR_DICTATE_ONNX_RUNTIME_DIRECTORY}/onnxruntime.dll")
    foreach(REQUIRED_MOONSHINE_FILE "${CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY}" "${CLEAR_DICTATE_ONNX_RUNTIME_DLL}")
        if(NOT EXISTS "${REQUIRED_MOONSHINE_FILE}")
            message(FATAL_ERROR "Required Moonshine Debug build input is missing: ${REQUIRED_MOONSHINE_FILE}")
        endif()
    endforeach()
    file(SIZE "${CLEAR_DICTATE_ONNX_RUNTIME_DLL}" CLEAR_DICTATE_ONNX_RUNTIME_DLL_ACTUAL_SIZE)
    file(SHA256 "${CLEAR_DICTATE_ONNX_RUNTIME_DLL}" CLEAR_DICTATE_ONNX_RUNTIME_DLL_ACTUAL_SHA256)
    file(SIZE "${CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY}" CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY_ACTUAL_SIZE)
    file(SHA256 "${CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY}" CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY_ACTUAL_SHA256)
    if(NOT CLEAR_DICTATE_ONNX_RUNTIME_DLL_ACTUAL_SIZE EQUAL CLEAR_DICTATE_ONNX_RUNTIME_DLL_SIZE_BYTES OR
       NOT CLEAR_DICTATE_ONNX_RUNTIME_DLL_ACTUAL_SHA256 STREQUAL CLEAR_DICTATE_ONNX_RUNTIME_DLL_SHA256)
        message(FATAL_ERROR "The pinned ONNX Runtime DLL failed size or SHA-256 verification.")
    endif()
    if(NOT CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY_ACTUAL_SIZE EQUAL CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY_SIZE_BYTES OR
       NOT CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY_ACTUAL_SHA256 STREQUAL CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY_SHA256)
        message(FATAL_ERROR "The pinned ONNX Runtime import library failed size or SHA-256 verification.")
    endif()

    set(
        CLEAR_DICTATE_MOONSHINE_CONFIGURE_COMMAND
        "${CMAKE_COMMAND}"
        -S "${MOONSHINE_SOURCE_DIR}"
        -B "${MOONSHINE_BUILD_DIR}"
        -G "${CMAKE_GENERATOR}"
        -DMOONSHINE_BUILD_SHARED=OFF
        -DMOONSHINE_TTS_BUILD_ONNX=ON
    )
    if(CMAKE_GENERATOR_PLATFORM)
        list(APPEND CLEAR_DICTATE_MOONSHINE_CONFIGURE_COMMAND -A "${CMAKE_GENERATOR_PLATFORM}")
    endif()
    if(CMAKE_GENERATOR_TOOLSET)
        list(APPEND CLEAR_DICTATE_MOONSHINE_CONFIGURE_COMMAND -T "${CMAKE_GENERATOR_TOOLSET}")
    endif()

    add_custom_target(
        clear_dictate_moonshine_dependency
        COMMAND ${CLEAR_DICTATE_MOONSHINE_CONFIGURE_COMMAND}
        COMMAND "${CMAKE_COMMAND}" --build "${MOONSHINE_BUILD_DIR}" --config Debug --target moonshine --clean-first
        BYPRODUCTS
            "${CLEAR_DICTATE_MOONSHINE_LIBRARY}"
            "${CLEAR_DICTATE_BIN_TOKENIZER_LIBRARY}"
            "${CLEAR_DICTATE_MOONSHINE_UTILS_LIBRARY}"
            "${CLEAR_DICTATE_ORT_UTILS_LIBRARY}"
        COMMENT "Building the exact pinned Moonshine source and transitive archives in Debug configuration"
        VERBATIM
    )

    add_library(clear_dictate_moonshine_speech_engine src/MoonshineSpeechEngine.cpp)
    add_dependencies(clear_dictate_moonshine_speech_engine clear_dictate_moonshine_dependency)
    target_include_directories(clear_dictate_moonshine_speech_engine PUBLIC include PRIVATE "${MOONSHINE_SOURCE_DIR}/core")
    target_link_libraries(
        clear_dictate_moonshine_speech_engine
        PUBLIC clear_dictate_worker_transcript_payloads
        PRIVATE
        clear_dictate_verified_model_file
        "${CLEAR_DICTATE_MOONSHINE_LIBRARY}"
        "${CLEAR_DICTATE_BIN_TOKENIZER_LIBRARY}"
        "${CLEAR_DICTATE_MOONSHINE_UTILS_LIBRARY}"
        "${CLEAR_DICTATE_ORT_UTILS_LIBRARY}"
        "${CLEAR_DICTATE_ONNX_RUNTIME_IMPORT_LIBRARY}"
    )
    target_compile_features(clear_dictate_moonshine_speech_engine PUBLIC cxx_std_20)
    set_target_properties(clear_dictate_moonshine_speech_engine PROPERTIES CXX_EXTENSIONS OFF)
    target_compile_options(clear_dictate_moonshine_speech_engine PRIVATE /EHsc /W4)

    add_executable(clear_dictate_live_microphone_moonshine_pipeline_smoke tests/LiveMicrophoneMoonshinePipelineSmoke.cpp)
    target_link_libraries(
        clear_dictate_live_microphone_moonshine_pipeline_smoke
        PRIVATE
        clear_dictate_moonshine_speech_engine
        clear_dictate_windows_audio_session_capture
    )
    target_compile_features(clear_dictate_live_microphone_moonshine_pipeline_smoke PRIVATE cxx_std_20)
    set_target_properties(clear_dictate_live_microphone_moonshine_pipeline_smoke PROPERTIES CXX_EXTENSIONS OFF)
    target_compile_options(clear_dictate_live_microphone_moonshine_pipeline_smoke PRIVATE /EHsc /W4 /wd4324)
    add_custom_command(
        TARGET clear_dictate_live_microphone_moonshine_pipeline_smoke
        POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${CLEAR_DICTATE_ONNX_RUNTIME_DLL}" "$<TARGET_FILE_DIR:clear_dictate_live_microphone_moonshine_pipeline_smoke>"
    )

    option(CLEAR_DICTATE_ENABLE_SPEECH_MODEL_INTEGRATION_TESTS "Build the pinned Moonshine spoken-fixture integration test." OFF)
    if(CLEAR_DICTATE_ENABLE_SPEECH_MODEL_INTEGRATION_TESTS)
        if(NOT DEFINED CLEAR_DICTATE_TEST_SPEECH_MODEL_DIRECTORY OR NOT DEFINED CLEAR_DICTATE_TEST_SPEECH_WAVE)
            message(FATAL_ERROR "The speech model directory and wave fixture are required for integration tests.")
        endif()
        file(SIZE "${CLEAR_DICTATE_TEST_SPEECH_WAVE}" CLEAR_DICTATE_SPOKEN_FIXTURE_ACTUAL_SIZE)
        file(SHA256 "${CLEAR_DICTATE_TEST_SPEECH_WAVE}" CLEAR_DICTATE_SPOKEN_FIXTURE_ACTUAL_SHA256)
        if(NOT CLEAR_DICTATE_SPOKEN_FIXTURE_ACTUAL_SIZE EQUAL CLEAR_DICTATE_SPOKEN_FIXTURE_SIZE_BYTES OR
           NOT CLEAR_DICTATE_SPOKEN_FIXTURE_ACTUAL_SHA256 STREQUAL CLEAR_DICTATE_SPOKEN_FIXTURE_SHA256)
            message(FATAL_ERROR "The pinned spoken fixture failed size or SHA-256 verification.")
        endif()

        add_executable(clear_dictate_moonshine_speech_engine_tests tests/MoonshineSpeechEngineIntegrationTests.cpp)
        target_link_libraries(clear_dictate_moonshine_speech_engine_tests PRIVATE clear_dictate_moonshine_speech_engine)
        target_compile_features(clear_dictate_moonshine_speech_engine_tests PRIVATE cxx_std_20)
        set_target_properties(clear_dictate_moonshine_speech_engine_tests PROPERTIES CXX_EXTENSIONS OFF)
        target_compile_options(clear_dictate_moonshine_speech_engine_tests PRIVATE /EHsc /W4)
        add_custom_command(
            TARGET clear_dictate_moonshine_speech_engine_tests
            POST_BUILD
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${CLEAR_DICTATE_ONNX_RUNTIME_DLL}" "$<TARGET_FILE_DIR:clear_dictate_moonshine_speech_engine_tests>"
        )
        add_test(
            NAME clear_dictate_moonshine_speech_engine_tests
            COMMAND clear_dictate_moonshine_speech_engine_tests "${CLEAR_DICTATE_TEST_SPEECH_MODEL_DIRECTORY}" "${CLEAR_DICTATE_TEST_SPEECH_WAVE}"
        )
    endif()
endif()

option(CLEAR_DICTATE_ENABLE_LLAMA "Build the project-owned llama.cpp text-generation adapter." OFF)

if(CLEAR_DICTATE_ENABLE_LLAMA)
    if(NOT DEFINED LLAMA_CPP_SOURCE_DIR)
        message(FATAL_ERROR "LLAMA_CPP_SOURCE_DIR must identify the pinned llama.cpp source checkout.")
    endif()

    include(dependencies/llama-lock.cmake)
    include(dependencies/qwen-model-lock.cmake)

    string(LENGTH "${CLEAR_DICTATE_QWEN_SHA256}" CLEAR_DICTATE_QWEN_SHA256_CHARACTER_COUNT)
    if(NOT CLEAR_DICTATE_QWEN_SHA256_CHARACTER_COUNT EQUAL 64 OR CLEAR_DICTATE_QWEN_SHA256 MATCHES "[^0-9a-fA-F]")
        message(FATAL_ERROR "The locked Qwen SHA-256 digest must contain exactly 64 hexadecimal characters.")
    endif()

    string(REGEX MATCHALL "[0-9a-fA-F][0-9a-fA-F]" CLEAR_DICTATE_QWEN_SHA256_BYTE_PAIRS "${CLEAR_DICTATE_QWEN_SHA256}")
    list(TRANSFORM CLEAR_DICTATE_QWEN_SHA256_BYTE_PAIRS PREPEND "0x")
    list(JOIN CLEAR_DICTATE_QWEN_SHA256_BYTE_PAIRS ", " CLEAR_DICTATE_QWEN_SHA256_BYTES)

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

    if(WIN32)
        set(CLEAR_DICTATE_GENERATED_INCLUDE_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
        file(MAKE_DIRECTORY "${CLEAR_DICTATE_GENERATED_INCLUDE_DIR}/clear_dictate")
        configure_file(
            dependencies/LockedQwenModel.h.in
            "${CLEAR_DICTATE_GENERATED_INCLUDE_DIR}/clear_dictate/LockedQwenModel.h"
            @ONLY
        )

        add_executable(clear_dictate_worker src/WorkerMain.cpp)
        target_include_directories(clear_dictate_worker PRIVATE "${CLEAR_DICTATE_GENERATED_INCLUDE_DIR}")
        target_link_libraries(
            clear_dictate_worker
            PRIVATE
            clear_dictate_llama_text_engine
            clear_dictate_text_inference_worker_session
            clear_dictate_verified_model_file
        )
        target_compile_features(clear_dictate_worker PRIVATE cxx_std_17)
        set_target_properties(clear_dictate_worker PROPERTIES CXX_EXTENSIONS OFF)

        if(MSVC)
            target_compile_options(clear_dictate_worker PRIVATE /EHsc /W4)
        endif()
    endif()

    option(CLEAR_DICTATE_ENABLE_MODEL_INTEGRATION_TESTS "Build tests that load the pinned local Qwen model." OFF)

    if(CLEAR_DICTATE_ENABLE_MODEL_INTEGRATION_TESTS)
        if(NOT DEFINED CLEAR_DICTATE_TEST_TEXT_MODEL OR CLEAR_DICTATE_TEST_TEXT_MODEL STREQUAL "")
            message(FATAL_ERROR "CLEAR_DICTATE_TEST_TEXT_MODEL must identify the pinned Qwen model when integration tests are enabled.")
        endif()

        add_executable(clear_dictate_llama_text_engine_tests tests/LlamaTextEngineTests.cpp)
        target_include_directories(clear_dictate_llama_text_engine_tests PRIVATE "${CLEAR_DICTATE_GENERATED_INCLUDE_DIR}")
        target_link_libraries(
            clear_dictate_llama_text_engine_tests
            PRIVATE
            clear_dictate_llama_text_engine
            clear_dictate_verified_model_file
        )
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
