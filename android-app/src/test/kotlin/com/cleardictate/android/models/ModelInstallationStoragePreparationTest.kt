package com.cleardictate.android.models

import com.cleardictate.models.ClearDictateModelCatalog
import com.cleardictate.models.ModelInstallationStatus
import java.io.IOException
import java.nio.file.Path
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Specifies stable worker outcomes for failures before the model installer can start.
 */
class ModelInstallationStoragePreparationTest
{
    private val installationRoot = Path.of("private-model-root")
    private val manifestGroup = ClearDictateModelCatalog.moonshineTinyStreamingEnglish

    @Test
    fun `ready private storage continues into model installation`()
    {
        val status = prepareModelInstallationStorage(
            installationRoot = installationRoot,
            manifestGroup = manifestGroup,
            createDirectories = { it },
            hasSufficientStorage = { _, _ -> true }
        )

        assertNull(status)
    }

    @Test
    fun `directory input output failure is classified as transient`()
    {
        val status = prepareModelInstallationStorage(
            installationRoot = installationRoot,
            manifestGroup = manifestGroup,
            createDirectories = {
                throw IOException("simulated directory failure")
            },
            hasSufficientStorage = { _, _ -> true }
        )

        assertEquals(ModelInstallationStatus.TRANSIENT_INPUT_OUTPUT_FAILURE, status)
    }

    @Test
    fun `storage query security failure is classified as permanent input output failure`()
    {
        val status = prepareModelInstallationStorage(
            installationRoot = installationRoot,
            manifestGroup = manifestGroup,
            createDirectories = { it },
            hasSufficientStorage = { _, _ ->
                throw SecurityException("simulated private-storage denial")
            }
        )

        assertEquals(ModelInstallationStatus.INPUT_OUTPUT_FAILURE, status)
    }
}
