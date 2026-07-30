package com.cleardictate.models

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies size and digest verification before a model can be loaded.
 */
class ModelManifestVerifierTest
{
    @Test
    fun `accepts an exact model file and rejects changed contents`()
    {
        val modelFile = Files.createTempFile("cleardictate-model-verification", ".bin")

        try
        {
            modelFile.writeText("verified model bytes")
            val manifestEntry = ModelManifestEntry(
                logicalIdentifier = "test-model",
                sourceRepository = "clear-dictate/test-fixture",
                sourceRevision = "test-revision-1",
                sourceUri = "https://invalid.example/test-fixture",
                exactFilename = modelFile.fileName.toString(),
                expectedByteCount = Files.size(modelFile),
                sha256Digest = "03cfa25d83f5eaa1faac98ed6ceaaf0e7afe3c273a1e1502c2714ebe10b8263e",
                licenceIdentifier = "Apache-2.0",
                modelSchemaVersion = 1
            )

            assertTrue(ModelManifestVerifier.verify(modelFile, manifestEntry).verified)

            modelFile.writeText("changed model bytes")

            val failedResult = ModelManifestVerifier.verify(modelFile, manifestEntry)
            assertFalse(failedResult.verified)
            assertEquals(ModelVerificationFailure.SIZE_MISMATCH, failedResult.failure)
        }
        finally
        {
            Files.deleteIfExists(modelFile)
        }
    }
}
