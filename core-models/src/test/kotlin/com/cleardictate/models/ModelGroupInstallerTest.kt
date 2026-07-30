package com.cleardictate.models

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies resumable, verified, atomic installation before Android scheduling is involved.
 */
class ModelGroupInstallerTest
{
    @Test
    fun `valid components activate only after the complete group verifies`()
    {
        val installationRoot = Files.createTempDirectory("cleardictate-model-install")
        val manifestGroup = manifestGroup()
        val source = RecordingModelArtifactSource(expectedArtifacts())

        try
        {
            val result = ModelGroupInstaller().install(installationRoot, manifestGroup, source)
            val targetDirectory = installationRoot.resolve(ModelStorageLayout.versionDirectoryName(manifestGroup))

            assertEquals(ModelInstallationStatus.INSTALLED, result.status)
            assertEquals("first model".toByteArray().toList(), targetDirectory.resolve("first.ort").readBytes().toList())
            assertEquals("second model".toByteArray().toList(), targetDirectory.resolve("second.bin").readBytes().toList())
        }
        finally
        {
            installationRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt component never becomes the active immutable directory`()
    {
        val installationRoot = Files.createTempDirectory("cleardictate-model-corrupt")
        val manifestGroup = manifestGroup()
        val corruptArtifacts = expectedArtifacts().toMutableMap().apply {
            this["second.bin"] = "corrupt".toByteArray()
        }

        try
        {
            val result = ModelGroupInstaller().install(
                installationRoot,
                manifestGroup,
                RecordingModelArtifactSource(corruptArtifacts)
            )
            val targetDirectory = installationRoot.resolve(ModelStorageLayout.versionDirectoryName(manifestGroup))

            assertEquals(ModelInstallationStatus.VERIFICATION_FAILED, result.status)
            assertEquals("second.bin", result.failedFilename)
            assertFalse(targetDirectory.exists())
        }
        finally
        {
            installationRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interrupted partial file resumes from its exact byte offset`()
    {
        val installationRoot = Files.createTempDirectory("cleardictate-model-resume")
        val manifestGroup = manifestGroup()
        val stagingDirectory = ModelGroupInstaller.stagingDirectory(installationRoot, manifestGroup)
        Files.createDirectories(stagingDirectory)
        stagingDirectory.resolve("first.ort.partial").writeBytes("first".toByteArray())
        val source = RecordingModelArtifactSource(expectedArtifacts())

        try
        {
            val result = ModelGroupInstaller().install(installationRoot, manifestGroup, source)

            assertEquals(ModelInstallationStatus.INSTALLED, result.status)
            assertEquals(5L, source.requestedOffsets.getValue("first.ort"))
        }
        finally
        {
            installationRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `complete verified partial file is promoted without a network request`()
    {
        val installationRoot = Files.createTempDirectory("cleardictate-model-complete-partial")
        val manifestGroup = manifestGroup()
        val stagingDirectory = ModelGroupInstaller.stagingDirectory(installationRoot, manifestGroup)
        Files.createDirectories(stagingDirectory)
        stagingDirectory.resolve("first.ort.partial").writeBytes(expectedArtifacts().getValue("first.ort"))
        val source = RecordingModelArtifactSource(expectedArtifacts())

        try
        {
            val result = ModelGroupInstaller().install(installationRoot, manifestGroup, source)

            assertEquals(ModelInstallationStatus.INSTALLED, result.status)
            assertFalse(source.requestedOffsets.containsKey("first.ort"))
        }
        finally
        {
            installationRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `complete corrupt partial file is deleted and downloaded from the beginning`()
    {
        val installationRoot = Files.createTempDirectory("cleardictate-model-corrupt-complete-partial")
        val manifestGroup = manifestGroup()
        val stagingDirectory = ModelGroupInstaller.stagingDirectory(installationRoot, manifestGroup)
        Files.createDirectories(stagingDirectory)
        stagingDirectory.resolve("first.ort.partial").writeBytes(ByteArray(expectedArtifacts().getValue("first.ort").size))
        val source = RecordingModelArtifactSource(expectedArtifacts())

        try
        {
            val result = ModelGroupInstaller().install(installationRoot, manifestGroup, source)

            assertEquals(ModelInstallationStatus.INSTALLED, result.status)
            assertEquals(0L, source.requestedOffsets.getValue("first.ort"))
        }
        finally
        {
            installationRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `already verified immutable directory performs no network reads`()
    {
        val installationRoot = Files.createTempDirectory("cleardictate-model-existing")
        val manifestGroup = manifestGroup()
        val source = RecordingModelArtifactSource(expectedArtifacts())

        try
        {
            assertEquals(
                ModelInstallationStatus.INSTALLED,
                ModelGroupInstaller().install(installationRoot, manifestGroup, source).status
            )
            val secondSource = RecordingModelArtifactSource(expectedArtifacts())

            val secondResult = ModelGroupInstaller().install(installationRoot, manifestGroup, secondSource)

            assertEquals(ModelInstallationStatus.ALREADY_INSTALLED, secondResult.status)
            assertTrue(secondSource.requestedOffsets.isEmpty())
        }
        finally
        {
            installationRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a second installer cannot mutate an installation held by another writer`()
    {
        val installationRoot = Files.createTempDirectory("cleardictate-model-lock")
        val manifestGroup = manifestGroup()
        val versionDirectoryName = ModelStorageLayout.versionDirectoryName(manifestGroup)
        val lockPath = installationRoot.resolve(".$versionDirectoryName.lock")

        try
        {
            java.nio.channels.FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { lockChannel ->
                lockChannel.lock().use {
                    val result = ModelGroupInstaller().install(
                        installationRoot,
                        manifestGroup,
                        RecordingModelArtifactSource(expectedArtifacts())
                    )

                    assertEquals(ModelInstallationStatus.INSTALLATION_BUSY, result.status)
                }
            }
        }
        finally
        {
            installationRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt immutable target is quarantined and repaired under the installation lock`()
    {
        val installationRoot = Files.createTempDirectory("cleardictate-model-repair")
        val manifestGroup = manifestGroup()
        val targetDirectory = installationRoot.resolve(ModelStorageLayout.versionDirectoryName(manifestGroup))
        Files.createDirectories(targetDirectory)
        targetDirectory.resolve("first.ort").writeBytes("damaged".toByteArray())

        try
        {
            val result = ModelGroupInstaller().install(
                installationRoot,
                manifestGroup,
                RecordingModelArtifactSource(expectedArtifacts())
            )

            assertEquals(ModelInstallationStatus.INSTALLED, result.status)
            assertFalse(
                Files.list(installationRoot).use { paths ->
                    paths.anyMatch { path -> path.fileName.toString().contains(".corrupt-") }
                }
            )
        }
        finally
        {
            installationRoot.toFile().deleteRecursively()
        }
    }

    private fun manifestGroup(): ModelManifestGroup
    {
        return ModelManifestGroup(
            logicalIdentifier = "test-download-model",
            displayName = "Test download model",
            files = expectedArtifacts().map { (filename, bytes) ->
                ModelManifestEntry(
                    logicalIdentifier = "test-download-model",
                    sourceRepository = "clear-dictate/test-fixture",
                    sourceRevision = "revision-1",
                    sourceUri = "https://invalid.example/$filename",
                    exactFilename = filename,
                    expectedByteCount = bytes.size.toLong(),
                    sha256Digest = sha256(bytes),
                    licenceIdentifier = "Apache-2.0",
                    modelSchemaVersion = 1
                )
            }
        )
    }

    private fun expectedArtifacts(): Map<String, ByteArray>
    {
        return linkedMapOf(
            "first.ort" to "first model".toByteArray(),
            "second.bin" to "second model".toByteArray()
        )
    }

    private fun sha256(bytes: ByteArray): String
    {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { digestByte -> "%02x".format(digestByte) }
    }
}

private class RecordingModelArtifactSource(
    private val artifacts: Map<String, ByteArray>
) : ModelArtifactSource
{
    val requestedOffsets = mutableMapOf<String, Long>()

    override fun open(manifestEntry: ModelManifestEntry, requestedOffset: Long): ModelArtifactDownload
    {
        requestedOffsets[manifestEntry.exactFilename] = requestedOffset
        val completeBytes = artifacts.getValue(manifestEntry.exactFilename)
        val safeOffset = requestedOffset.coerceIn(0L, completeBytes.size.toLong()).toInt()
        return ModelArtifactDownload(
            inputStream = ByteArrayInputStream(completeBytes.copyOfRange(safeOffset, completeBytes.size)),
            acceptedOffset = safeOffset.toLong()
        )
    }
}
