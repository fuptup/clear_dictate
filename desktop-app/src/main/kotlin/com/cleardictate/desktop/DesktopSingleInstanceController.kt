package com.cleardictate.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Owns one native single-instance resource and reports whether another process created it first.
 */
internal interface DesktopSingleInstanceHandle : AutoCloseable
{
    val alreadyExists: Boolean
}

/**
 * Isolates the Windows mutex and foreground-window operations from startup policy and unit tests.
 */
internal interface DesktopSingleInstancePlatform
{
    fun createNamedMutex(name: String): DesktopSingleInstanceHandle
    fun activateExistingWindow(title: String): Boolean
}

/**
 * Prevents duplicate UI and model-worker trees before application construction begins.
 */
internal class DesktopSingleInstanceController(private val platform: DesktopSingleInstancePlatform)
{
    /**
     * Returns the lifetime lease to the first process. Later processes activate the existing window and return no lease, which instructs their entry point to exit.
     */
    fun acquireOrActivate(): DesktopSingleInstanceHandle?
    {
        val instanceHandle = platform.createNamedMutex(MUTEX_NAME)
        if (!instanceHandle.alreadyExists)
        {
            return instanceHandle
        }

        instanceHandle.close()
        platform.activateExistingWindow(MAIN_WINDOW_TITLE)
        return null
    }

    private companion object
    {
        const val MUTEX_NAME = "Local\\ClearDictate.Desktop.Application"
        const val MAIN_WINDOW_TITLE = "ClearDictate"
    }
}

/**
 * Implements per-Windows-session process ownership and restores the existing Compose window when another launch is attempted.
 */
internal class WindowsDesktopSingleInstancePlatform : DesktopSingleInstancePlatform
{
    /**
     * Creates the named mutex without taking mutex ownership; retaining its handle is sufficient to keep the singleton object alive.
     */
    override fun createNamedMutex(name: String): DesktopSingleInstanceHandle
    {
        val nativeHandle = Kernel32SingleInstanceApi.INSTANCE.CreateMutexW(null, false, WString(name))
            ?: throw IllegalStateException("ClearDictate could not establish single-instance ownership.")
        val alreadyExists = Native.getLastError() == ERROR_ALREADY_EXISTS
        return WindowsNamedMutexHandle(nativeHandle, alreadyExists)
    }

    /**
     * Restores a minimized main window before requesting foreground activation. Failure is harmless because the duplicate process must still exit.
     */
    override fun activateExistingWindow(title: String): Boolean
    {
        val windowHandle = User32SingleInstanceApi.INSTANCE.FindWindowW(null, WString(title)) ?: return false
        User32SingleInstanceApi.INSTANCE.ShowWindow(windowHandle, SHOW_WINDOW_RESTORE)
        return User32SingleInstanceApi.INSTANCE.SetForegroundWindow(windowHandle)
    }

    private class WindowsNamedMutexHandle(private var nativeHandle: Pointer?, override val alreadyExists: Boolean) : DesktopSingleInstanceHandle
    {
        override fun close()
        {
            val handleToClose = synchronized(this)
            {
                nativeHandle.also { nativeHandle = null }
            } ?: return
            check(Kernel32SingleInstanceApi.INSTANCE.CloseHandle(handleToClose)) { "ClearDictate could not release single-instance ownership." }
        }
    }

    private companion object
    {
        const val ERROR_ALREADY_EXISTS = 183
        const val SHOW_WINDOW_RESTORE = 9
    }
}

/**
 * Declares only the Kernel32 calls required to create and release ClearDictate's named mutex.
 */
private interface Kernel32SingleInstanceApi : StdCallLibrary
{
    fun CreateMutexW(mutexAttributes: Pointer?, initialOwner: Boolean, name: WString): Pointer?
    fun CloseHandle(handle: Pointer): Boolean

    companion object
    {
        val INSTANCE: Kernel32SingleInstanceApi = Native.load("kernel32", Kernel32SingleInstanceApi::class.java, W32APIOptions.UNICODE_OPTIONS)
    }
}

/**
 * Declares only the User32 calls required to restore and foreground the existing ClearDictate window.
 */
private interface User32SingleInstanceApi : StdCallLibrary
{
    fun FindWindowW(className: WString?, windowName: WString): Pointer?
    fun ShowWindow(windowHandle: Pointer, command: Int): Boolean
    fun SetForegroundWindow(windowHandle: Pointer): Boolean

    companion object
    {
        val INSTANCE: User32SingleInstanceApi = Native.load("user32", User32SingleInstanceApi::class.java, W32APIOptions.UNICODE_OPTIONS)
    }
}
