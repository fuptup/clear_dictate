package com.cleardictate.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies that undo deletes only the exact last ClearDictate insertion in the same editor session.
 */
class LastInsertionUndoPolicyTest
{
    private val policy = LastInsertionUndoPolicy()
    private val session = EditorSessionIdentifier("editor-session")

    @Test
    fun `returns the exact deletion length when surrounding text still matches`()
    {
        val insertion = LastInsertion(session, " dictated text")

        assertEquals(insertion.insertedText.length, policy.charactersToDelete("Existing dictated text", session, insertion))
    }

    @Test
    fun `refuses deletion after manual editing or editor change`()
    {
        val insertion = LastInsertion(session, " dictated text")

        assertNull(policy.charactersToDelete("Existing edited text", session, insertion))
        assertNull(policy.charactersToDelete("Existing dictated text", EditorSessionIdentifier("other"), insertion))
    }

    @Test
    fun `refuses empty insertion records`()
    {
        assertNull(policy.charactersToDelete("Existing", session, LastInsertion(session, "")))
    }
}
