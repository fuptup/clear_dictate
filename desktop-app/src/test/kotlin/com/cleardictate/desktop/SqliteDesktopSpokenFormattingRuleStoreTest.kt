package com.cleardictate.desktop

import com.cleardictate.domain.SpokenFormattingSpacing
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies persistent custom-rule creation, editing, deletion, normalization, and live snapshot publication.
 */
class SqliteDesktopSpokenFormattingRuleStoreTest
{
    @Test
    fun `creates edits and deletes a custom spoken formatting rule`() = runTest {
        val store = SqliteDesktopSpokenFormattingRuleStore.open(Files.createTempDirectory("cleardictate-rules").resolve("history.sqlite"))

        store.save(null, "  Per   Cent  ", "%", SpokenFormattingSpacing.ATTACH_LEFT, true)
        val created = store.readAll().single()

        assertEquals("Per Cent", created.spokenPhrase)
        assertEquals("%", store.currentRules().single().replacement)

        store.save(created.identifier, "per cent", "pct", SpokenFormattingSpacing.PRESERVE, false)
        val updated = store.readAll().single()

        assertEquals("pct", updated.replacement)
        assertEquals(SpokenFormattingSpacing.PRESERVE, updated.spacing)
        assertEquals(false, updated.consumesRecognizerPunctuation)

        store.delete(updated.identifier)
        assertEquals(emptyList(), store.readAll())
        assertEquals(emptyList(), store.currentRules())
    }
}
