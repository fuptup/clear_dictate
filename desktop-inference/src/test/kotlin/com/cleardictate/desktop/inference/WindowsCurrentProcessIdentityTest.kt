package com.cleardictate.desktop.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsCurrentProcessIdentityTest
{
    @Test
    fun `captured identity matches the current Java process`()
    {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        {
            return
        }

        val identity = WindowsCurrentProcessIdentity.capture()

        assertEquals(ProcessHandle.current().pid(), identity.processIdentifier)
        assertTrue(identity.creationTimeTicks > 0)
    }
}
