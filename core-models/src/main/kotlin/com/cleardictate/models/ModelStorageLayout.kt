package com.cleardictate.models

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Produces immutable, version-addressed directory names shared by installers and inference owners.
 */
object ModelStorageLayout
{
    fun versionDirectoryName(manifestGroup: ModelManifestGroup): String
    {
        require(manifestGroup.files.isNotEmpty()) { "A model group must declare at least one file." }

        val schemaVersions = manifestGroup.files.map { manifestEntry ->
            manifestEntry.modelSchemaVersion
        }.distinct()
        require(schemaVersions.size == 1) { "Every file in a model group must use the same schema version." }

        val revisions = manifestGroup.files.map { manifestEntry ->
            manifestEntry.sourceRevision
        }.distinct()
        require(revisions.size == 1) { "Every file in a model group must use the same source revision." }

        val manifestFingerprint = calculateManifestFingerprint(manifestGroup)
        val safeRevision = revisions.single().filter { character ->
            character.isLetterOrDigit()
        }.take(12)

        return "schema-${schemaVersions.single()}-$safeRevision-${manifestFingerprint.take(12)}"
    }

    private fun calculateManifestFingerprint(manifestGroup: ModelManifestGroup): String
    {
        val canonicalManifest = manifestGroup.files.joinToString(separator = "\n") { manifestEntry ->
            "${manifestEntry.exactFilename}:${manifestEntry.expectedByteCount}:${manifestEntry.sha256Digest.lowercase()}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalManifest.toByteArray(StandardCharsets.UTF_8))

        return digest.joinToString(separator = "") { digestByte ->
            "%02x".format(digestByte)
        }
    }
}
