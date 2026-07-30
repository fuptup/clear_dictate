package com.cleardictate.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prevents unverified or mutable model metadata from entering production downloads.
 */
class ClearDictateModelCatalogTest
{
    @Test
    fun `catalogue contains the exact verified model byte counts`()
    {
        assertEquals(51_131_795L, ClearDictateModelCatalog.moonshineTinyStreamingEnglish.files.sumOf { it.expectedByteCount })
        assertEquals(491_400_032L, ClearDictateModelCatalog.qwenTranscriptPolisher.files.sumOf { it.expectedByteCount })
    }

    @Test
    fun `every file has a real digest licence and source revision`()
    {
        val allFiles = ClearDictateModelCatalog.requiredModelGroups.flatMap { it.files }

        for (modelFile in allFiles)
        {
            assertTrue(modelFile.sha256Digest.matches(Regex("""[0-9a-f]{64}""")))
            assertTrue(modelFile.sha256Digest.toSet().size > 1, "A repeated-character digest is a placeholder.")
            assertTrue(modelFile.licenceIdentifier.isNotBlank())
            assertTrue(modelFile.sourceRevision.isNotBlank())
            assertTrue(modelFile.expectedByteCount > 0)
        }
    }

    @Test
    fun `catalogue destinations are unique and Qwen uses an immutable revision address`()
    {
        val allFiles = ClearDictateModelCatalog.requiredModelGroups.flatMap { it.files }
        val destinationPaths = allFiles.map { "${it.logicalIdentifier}/${it.exactFilename}" }
        val qwenFile = ClearDictateModelCatalog.qwenTranscriptPolisher.files.single()

        assertEquals(destinationPaths.size, destinationPaths.distinct().size)
        assertTrue(qwenFile.sourceUri.contains(qwenFile.sourceRevision))
        assertTrue(!qwenFile.sourceUri.contains("/main/"))
    }
}
