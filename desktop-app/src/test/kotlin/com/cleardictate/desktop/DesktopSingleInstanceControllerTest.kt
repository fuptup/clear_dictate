package com.cleardictate.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Specifies first-launch ownership and duplicate-launch activation without invoking native Windows APIs.
 */
class DesktopSingleInstanceControllerTest
{
    @Test
    fun `first launch retains ownership without activating another window`()
    {
        val platform = RecordingSingleInstancePlatform(alreadyExists = false)

        val lease = DesktopSingleInstanceController(platform).acquireOrActivate()

        assertNotNull(lease)
        assertEquals(0, platform.activationCount)
        assertFalse(platform.handle.closed)
        lease.close()
        assertTrue(platform.handle.closed)
    }

    @Test
    fun `duplicate launch activates existing window and releases its duplicate handle`()
    {
        val platform = RecordingSingleInstancePlatform(alreadyExists = true)

        val lease = DesktopSingleInstanceController(platform).acquireOrActivate()

        assertNull(lease)
        assertEquals(1, platform.activationCount)
        assertEquals("ClearDictate", platform.activatedTitle)
        assertTrue(platform.handle.closed)
    }

    private class RecordingSingleInstancePlatform(alreadyExists: Boolean) : DesktopSingleInstancePlatform
    {
        val handle = RecordingSingleInstanceHandle(alreadyExists)
        var activationCount = 0
            private set
        var activatedTitle: String? = null
            private set

        override fun createNamedMutex(name: String): DesktopSingleInstanceHandle
        {
            assertEquals("Local\\ClearDictate.Desktop.Application", name)
            return handle
        }

        override fun activateExistingWindow(title: String): Boolean
        {
            activationCount += 1
            activatedTitle = title
            return true
        }
    }

    private class RecordingSingleInstanceHandle(override val alreadyExists: Boolean) : DesktopSingleInstanceHandle
    {
        var closed = false
            private set

        override fun close()
        {
            closed = true
        }
    }
}
