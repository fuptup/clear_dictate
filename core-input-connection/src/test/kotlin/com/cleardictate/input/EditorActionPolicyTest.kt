package com.cleardictate.input

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies platform-neutral handling of Android editor-action requests.
 */
class EditorActionPolicyTest
{
    @Test
    fun `uses each explicit supported editor action`()
    {
        SupportedEditorAction.entries
            .filterNot { action -> action == SupportedEditorAction.UNSPECIFIED }
            .forEach { action ->
                assertEquals(action, EditorActionPolicy.resolve(action, isMultiline = false))
            }
    }

    @Test
    fun `unspecified multiline fields insert a newline while single-line fields finish editing`()
    {
        assertEquals(SupportedEditorAction.ENTER, EditorActionPolicy.resolve(SupportedEditorAction.UNSPECIFIED, isMultiline = true))
        assertEquals(SupportedEditorAction.DONE, EditorActionPolicy.resolve(SupportedEditorAction.UNSPECIFIED, isMultiline = false))
    }
}
