package com.cleardictate.inference.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that completed local cancellation cannot accumulate operation identifiers when a remote callback is lost.
 */
class InferenceClientOperationOwnershipTest
{
    @Test
    fun `local cancellation immediately releases operation identity`()
    {
        val ownership = InferenceClientOperationOwnership()

        listOf("first-operation", "second-operation").forEach { operationIdentifier ->
            ownership.tryActivate(operationIdentifier)
            assertEquals(operationIdentifier, ownership.cancelActiveOperation())
        }

        assertEquals(0, ownership.retainedOperationCount())
    }

    @Test
    fun `late cancellation callback cannot clear a newer operation`()
    {
        val ownership = InferenceClientOperationOwnership()
        ownership.tryActivate("cancelled-operation")
        ownership.cancelActiveOperation()
        ownership.tryActivate("current-operation")

        assertFalse(ownership.completeCancellation("cancelled-operation"))
        assertTrue(ownership.isActive("current-operation"))
    }
}
