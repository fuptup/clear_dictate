package com.cleardictate.models

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Specifies all-or-nothing verification for multi-file native models.
 */
class VerifiedModelGroupLeaseManagerTest
{
    @Test
    fun `all verified files remain leased until the group lease closes`()
    {
        val modelDirectory = Files.createTempDirectory("cleardictate-model-group")
        val firstModelFile = modelDirectory.resolve("first.ort")
        val secondModelFile = modelDirectory.resolve("second.bin")
        firstModelFile.writeText("first model")
        secondModelFile.writeText("second model")
        val fileLeaseManager = VerifiedModelLeaseManager()
        val groupLeaseManager = VerifiedModelGroupLeaseManager(fileLeaseManager)

        try
        {
            val acquisition = groupLeaseManager.acquire(modelDirectory, manifestGroup(firstModelFile, secondModelFile))
            val groupLease = assertNotNull(acquisition.lease)

            assertFalse(fileLeaseManager.isReplacementAllowed(firstModelFile))
            assertFalse(fileLeaseManager.isReplacementAllowed(secondModelFile))

            groupLease.close()

            assertTrue(fileLeaseManager.isReplacementAllowed(firstModelFile))
            assertTrue(fileLeaseManager.isReplacementAllowed(secondModelFile))
        }
        finally
        {
            Files.deleteIfExists(firstModelFile)
            Files.deleteIfExists(secondModelFile)
            Files.deleteIfExists(modelDirectory)
        }
    }

    @Test
    fun `one corrupt component rolls back every earlier component lease`()
    {
        val modelDirectory = Files.createTempDirectory("cleardictate-corrupt-model-group")
        val firstModelFile = modelDirectory.resolve("first.ort")
        val secondModelFile = modelDirectory.resolve("second.bin")
        firstModelFile.writeText("first model")
        secondModelFile.writeText("corrupt bytes")
        val fileLeaseManager = VerifiedModelLeaseManager()
        val groupLeaseManager = VerifiedModelGroupLeaseManager(fileLeaseManager)

        try
        {
            val acquisition = groupLeaseManager.acquire(modelDirectory, manifestGroup(firstModelFile, secondModelFile))

            assertNull(acquisition.lease)
            assertEquals(ModelVerificationFailure.SIZE_MISMATCH, acquisition.failure)
            assertEquals("second.bin", acquisition.failedFilename)
            assertTrue(fileLeaseManager.isReplacementAllowed(firstModelFile))
            assertTrue(fileLeaseManager.isReplacementAllowed(secondModelFile))
        }
        finally
        {
            Files.deleteIfExists(firstModelFile)
            Files.deleteIfExists(secondModelFile)
            Files.deleteIfExists(modelDirectory)
        }
    }

    private fun manifestGroup(firstModelFile: java.nio.file.Path, secondModelFile: java.nio.file.Path): ModelManifestGroup
    {
        return ModelManifestGroup(
            logicalIdentifier = "test-model-group",
            displayName = "Test model group",
            files = listOf(
                manifestEntry(firstModelFile, "first model"),
                manifestEntry(secondModelFile, "second model")
            )
        )
    }

    private fun manifestEntry(modelFile: java.nio.file.Path, expectedContents: String): ModelManifestEntry
    {
        val expectedBytes = expectedContents.toByteArray()
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(expectedBytes)
            .joinToString(separator = "") { digestByte -> "%02x".format(digestByte) }

        return ModelManifestEntry(
            logicalIdentifier = "test-model-group",
            sourceRepository = "clear-dictate/test-fixture",
            sourceRevision = "test-revision-1",
            sourceUri = "https://invalid.example/${modelFile.fileName}",
            exactFilename = modelFile.fileName.toString(),
            expectedByteCount = expectedBytes.size.toLong(),
            sha256Digest = expectedDigest,
            licenceIdentifier = "Apache-2.0",
            modelSchemaVersion = 1
        )
    }
}
