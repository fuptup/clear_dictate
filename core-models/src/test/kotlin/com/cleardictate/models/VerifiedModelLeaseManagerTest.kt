package com.cleardictate.models

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that trusted model bytes remain leased from verification through native ownership.
 */
class VerifiedModelLeaseManagerTest
{
    @Test
    fun `verified file blocks managed replacement until its lease closes`()
    {
        val modelFile = Files.createTempFile("cleardictate-model-lease", ".bin")
        val leaseManager = VerifiedModelLeaseManager()

        try
        {
            modelFile.writeText("verified model bytes")
            val acquisition = leaseManager.acquire(modelFile, manifestFor(modelFile))
            val lease = assertNotNull(acquisition.lease)

            assertTrue(lease.isOpen)
            assertFalse(leaseManager.isReplacementAllowed(modelFile))

            lease.close()

            assertFalse(lease.isOpen)
            assertTrue(leaseManager.isReplacementAllowed(modelFile))
        }
        finally
        {
            Files.deleteIfExists(modelFile)
        }
    }

    @Test
    fun `changed file never receives a lease`()
    {
        val modelFile = Files.createTempFile("cleardictate-model-lease-invalid", ".bin")
        val leaseManager = VerifiedModelLeaseManager()

        try
        {
            modelFile.writeText("changed model bytes")
            val acquisition = leaseManager.acquire(modelFile, manifestFor(modelFile))

            assertNull(acquisition.lease)
            assertEquals(ModelVerificationFailure.SIZE_MISMATCH, acquisition.failure)
            assertTrue(leaseManager.isReplacementAllowed(modelFile))
        }
        finally
        {
            Files.deleteIfExists(modelFile)
        }
    }

    private fun manifestFor(modelFile: java.nio.file.Path): ModelManifestEntry
    {
        return ModelManifestEntry(
            logicalIdentifier = "test-model",
            sourceRepository = "clear-dictate/test-fixture",
            sourceRevision = "test-revision-1",
            sourceUri = "https://invalid.example/test-fixture",
            exactFilename = modelFile.fileName.toString(),
            expectedByteCount = "verified model bytes".toByteArray().size.toLong(),
            sha256Digest = "03cfa25d83f5eaa1faac98ed6ceaaf0e7afe3c273a1e1502c2714ebe10b8263e",
            licenceIdentifier = "Apache-2.0",
            modelSchemaVersion = 1
        )
    }
}
