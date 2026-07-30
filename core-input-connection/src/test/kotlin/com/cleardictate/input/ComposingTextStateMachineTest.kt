package com.cleardictate.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies that transient partial text is cleared for every lifecycle event that invalidates it.
 */
class ComposingTextStateMachineTest
{
    @Test
    fun `clears composing text when editor or keyboard lifecycle changes`()
    {
        ComposingTextInvalidation.entries.forEach { invalidation ->
            val stateMachine = ComposingTextStateMachine()
            stateMachine.update("partial transcript")

            val command = stateMachine.invalidate(invalidation)

            assertEquals(ComposingTextCommand.RemoveFromEditor, command)
            assertNull(stateMachine.currentText)
        }
    }

    @Test
    fun `does not request redundant editor mutation without composing text`()
    {
        val stateMachine = ComposingTextStateMachine()

        assertEquals(ComposingTextCommand.None, stateMachine.invalidate(ComposingTextInvalidation.INPUT_CONNECTION_LOST))
    }

    @Test
    fun `blank partial removes a previous composing region`()
    {
        val stateMachine = ComposingTextStateMachine()
        stateMachine.update("partial transcript")

        val command = stateMachine.update("")

        assertEquals(ComposingTextCommand.RemoveFromEditor, command)
        assertNull(stateMachine.currentText)
    }
}
