package com.cleardictate.inference.service

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * Verifies that a lifecycle cancellation cannot be lost before Android starts microphone work.
 */
class PendingOperationTerminalRegistryTest
{
    @Test
    fun `cancel recorded before service start is consumed exactly once`()
    {
        val registry = PendingOperationTerminalRegistry()

        registry.recordCancel("client", "operation")

        assertEquals(
            PendingOperationTerminalAction.CANCEL,
            registry.consume("client", "operation")
        )
        assertNull(registry.consume("client", "operation"))
    }

    @Test
    fun `cancel dominates an earlier or later stop request`()
    {
        val registry = PendingOperationTerminalRegistry()

        registry.recordStop("client", "first-operation")
        registry.recordCancel("client", "first-operation")
        registry.recordCancel("client", "second-operation")
        registry.recordStop("client", "second-operation")

        assertEquals(
            PendingOperationTerminalAction.CANCEL,
            registry.consume("client", "first-operation")
        )
        assertEquals(
            PendingOperationTerminalAction.CANCEL,
            registry.consume("client", "second-operation")
        )
    }

    @Test
    fun `retention is bounded and client teardown clears remaining actions`()
    {
        val registry = PendingOperationTerminalRegistry(maximumRetainedActions = 2)

        registry.recordCancel("client", "oldest")
        registry.recordCancel("client", "middle")
        registry.recordCancel("client", "newest")

        assertNull(registry.consume("client", "oldest"))
        assertEquals(PendingOperationTerminalAction.CANCEL, registry.consume("client", "middle"))
        registry.recordStop("client", "remaining")
        registry.clearClient("client")
        assertNull(registry.consume("client", "newest"))
        assertNull(registry.consume("client", "remaining"))
    }
}
