package com.cleardictate.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies the exact emergency threshold and one-shot shutdown behavior without reading host memory.
 */
class DesktopSystemMemoryGuardTest
{
    @Test
    fun `exactly ninety five percent does not initiate shutdown`()
    {
        val guard = DesktopSystemMemoryGuard(FixedSystemMemoryLoadProvider(95.0))

        assertFalse(guard.shouldInitiateShutdown())
    }

    @Test
    fun `above ninety five percent initiates shutdown exactly once`()
    {
        val guard = DesktopSystemMemoryGuard(FixedSystemMemoryLoadProvider(95.01))

        assertTrue(guard.shouldInitiateShutdown())
        assertFalse(guard.shouldInitiateShutdown())
    }

    private class FixedSystemMemoryLoadProvider(private val memoryLoadPercentage: Double) : DesktopSystemMemoryLoadProvider
    {
        override fun currentMemoryLoadPercentage(): Double
        {
            return memoryLoadPercentage
        }
    }
}
