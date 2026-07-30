package com.cleardictate.desktop.inference

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

data class WindowsProcessIdentity(
    val processIdentifier: Long,
    val creationTimeTicks: Long
)
{
    init
    {
        require(processIdentifier in 1..UNSIGNED_INT_MAXIMUM) { "Windows process identifier is outside the supported range." }
        require(creationTimeTicks > 0) { "Windows process creation time must be positive." }
    }

    private companion object
    {
        const val UNSIGNED_INT_MAXIMUM = 0xFFFF_FFFFL
    }
}

class WindowsProcessIdentityException : IllegalStateException(
    "The Windows host process identity could not be established."
)

/**
 * Captures the current process identifier together with its exact Windows creation
 * timestamp. The launcher validates both values before it starts a model worker,
 * preventing a recycled process identifier from extending worker lifetime.
 */
object WindowsCurrentProcessIdentity
{
    fun capture(): WindowsProcessIdentity
    {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        {
            throw WindowsProcessIdentityException()
        }

        val processIdentifier = Kernel32ProcessIdentityApi.INSTANCE.GetCurrentProcessId().toLong() and 0xFFFF_FFFFL
        val creationTime = Memory(Long.SIZE_BYTES.toLong())
        val exitTime = Memory(Long.SIZE_BYTES.toLong())
        val kernelTime = Memory(Long.SIZE_BYTES.toLong())
        val userTime = Memory(Long.SIZE_BYTES.toLong())

        if (!Kernel32ProcessIdentityApi.INSTANCE.GetProcessTimes(
                Kernel32ProcessIdentityApi.INSTANCE.GetCurrentProcess(),
                creationTime,
                exitTime,
                kernelTime,
                userTime
            ))
        {
            throw WindowsProcessIdentityException()
        }

        return WindowsProcessIdentity(
            processIdentifier = processIdentifier,
            creationTimeTicks = creationTime.getLong(0)
        )
    }
}

private interface Kernel32ProcessIdentityApi : StdCallLibrary
{
    fun GetCurrentProcess(): Pointer
    fun GetCurrentProcessId(): Int
    fun GetProcessTimes(process: Pointer, creationTime: Pointer, exitTime: Pointer, kernelTime: Pointer, userTime: Pointer): Boolean

    companion object
    {
        val INSTANCE: Kernel32ProcessIdentityApi = Native.load(
            "kernel32",
            Kernel32ProcessIdentityApi::class.java,
            W32APIOptions.UNICODE_OPTIONS
        )
    }
}
