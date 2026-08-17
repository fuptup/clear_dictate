package com.cleardictate.desktop

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supplies the percentage of physical system memory currently occupied by Windows.
 */
internal fun interface DesktopSystemMemoryLoadProvider
{
    fun currentMemoryLoadPercentage(): Double
}

/**
 * Converts the JDK's host-level physical memory counters into an occupied-memory percentage.
 */
internal class WindowsDesktopSystemMemoryLoadProvider(
    private val operatingSystem: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
) : DesktopSystemMemoryLoadProvider
{
    override fun currentMemoryLoadPercentage(): Double
    {
        val totalBytes = operatingSystem.totalMemorySize
        val occupiedBytes = totalBytes - operatingSystem.freeMemorySize
        check(totalBytes > 0L) { "Windows did not report a valid physical memory capacity." }

        return occupiedBytes.toDouble() * 100.0 / totalBytes.toDouble()
    }
}

/**
 * Latches the first critical-memory observation so application shutdown is requested only once.
 */
internal class DesktopSystemMemoryGuard(private val memoryLoadProvider: DesktopSystemMemoryLoadProvider)
{
    private val shutdownInitiated = AtomicBoolean(false)

    /**
     * Returns true once when physical RAM occupancy first exceeds the configured emergency threshold.
     */
    fun shouldInitiateShutdown(): Boolean
    {
        if (memoryLoadProvider.currentMemoryLoadPercentage() <= EMERGENCY_SYSTEM_MEMORY_LOAD_PERCENTAGE)
        {
            return false
        }

        return shutdownInitiated.compareAndSet(false, true)
    }

    private companion object
    {
        const val EMERGENCY_SYSTEM_MEMORY_LOAD_PERCENTAGE = 95.0
    }
}
