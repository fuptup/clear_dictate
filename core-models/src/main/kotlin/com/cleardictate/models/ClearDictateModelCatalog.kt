package com.cleardictate.models

/**
 * Contains only model artifacts whose bytes were independently downloaded and verified.
 *
 * Moonshine currently publishes its model files at unversioned content-delivery addresses. The
 * resolving Moonshine release commit and exact digest are therefore both pinned. A changed asset
 * fails verification rather than silently becoming a new production model.
 */
object ClearDictateModelCatalog
{
    const val MODEL_SCHEMA_VERSION = 1
    const val MOONSHINE_RELEASE_COMMIT = "cc1695646a560f2eec7f7c058f3c4d580f039e4b"
    const val QWEN_REPOSITORY_REVISION = "9217f5db79a29953eb74d5343926648285ec7e67"

    private const val MOONSHINE_MODEL_BASE_URI =
        "https://download.moonshine.ai/model/tiny-streaming-en/quantized"
    private const val QWEN_MODEL_BASE_URI =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/$QWEN_REPOSITORY_REVISION"

    val moonshineTinyStreamingEnglish = ModelManifestGroup(
        logicalIdentifier = "moonshine-tiny-streaming-english",
        displayName = "Moonshine Tiny Streaming English",
        files = listOf(
            moonshineFile("adapter.ort", 1_319_440L, "df13e655b29d279911fcb42d8b91b0e655b8fe32b7ba1f463ece663ce55ae6eb"),
            moonshineFile("cross_kv.ort", 1_264_384L, "5acfca68f7bb068c68c1960b54e215995ba07ee46b61645b78bff010a14e5a92"),
            moonshineFile("decoder_kv.ort", 32_403_688L, "6e3828f1db4b634bc525cb8ba1f0b628ec56059168f0336ad060891c7c1c9154"),
            moonshineFile("encoder.ort", 7_569_200L, "96dde726be90c4429f3bc458d04e3ea5bd1818a5fdcd0152edf4c07b8e405c07"),
            moonshineFile("frontend.ort", 8_324_600L, "bbdf5edb120cb3df1adf9ebc07c35136539b007a7047fd148c6f2960fc56fcf1"),
            moonshineFile("streaming_config.json", 509L, "74fe5ddebd63b17caf59e8a3b18c17547ff7bce1642050edbb1c3962674f8950"),
            moonshineFile("tokenizer.bin", 249_974L, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d")
        )
    )

    val qwenTranscriptPolisher = ModelManifestGroup(
        logicalIdentifier = "qwen2.5-0.5b-instruct-q4-k-m",
        displayName = "Qwen 2.5 0.5B Instruct Q4_K_M",
        files = listOf(
            ModelManifestEntry(
                logicalIdentifier = "qwen2.5-0.5b-instruct-q4-k-m",
                sourceRepository = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                sourceRevision = QWEN_REPOSITORY_REVISION,
                sourceUri = "$QWEN_MODEL_BASE_URI/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                exactFilename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                expectedByteCount = 491_400_032L,
                sha256Digest = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
                licenceIdentifier = "Apache-2.0",
                modelSchemaVersion = MODEL_SCHEMA_VERSION
            )
        )
    )

    val requiredModelGroups: List<ModelManifestGroup> = listOf(
        moonshineTinyStreamingEnglish,
        qwenTranscriptPolisher
    )

    private fun moonshineFile(filename: String, byteCount: Long, sha256Digest: String): ModelManifestEntry
    {
        return ModelManifestEntry(
            logicalIdentifier = "moonshine-tiny-streaming-english",
            sourceRepository = "moonshine-ai/moonshine",
            sourceRevision = MOONSHINE_RELEASE_COMMIT,
            sourceUri = "$MOONSHINE_MODEL_BASE_URI/$filename",
            exactFilename = filename,
            expectedByteCount = byteCount,
            sha256Digest = sha256Digest,
            licenceIdentifier = "MIT",
            modelSchemaVersion = MODEL_SCHEMA_VERSION
        )
    }
}
