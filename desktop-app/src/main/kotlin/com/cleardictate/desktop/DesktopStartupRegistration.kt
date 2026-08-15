package com.cleardictate.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.nio.file.Path

/**
 * Controls whether ClearDictate is registered to launch when the current Windows user signs in.
 */
internal interface DesktopStartupRegistration
{
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

/**
 * Persists the startup option in the standard per-user Windows Run key, which does not require administrator permission.
 */
internal class WindowsDesktopStartupRegistration private constructor(executablePath: Path) : DesktopStartupRegistration
{
    private val launchCommand = "\"${executablePath.toAbsolutePath().normalize()}\""

    /**
     * Reports enabled only when the Run entry launches this exact ClearDictate executable, so stale installation paths are not presented as working.
     */
    override fun isEnabled(): Boolean
    {
        if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME))
        {
            return false
        }
        return Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME).equals(launchCommand, ignoreCase = true)
    }

    /**
     * Creates or removes the current user's startup entry without affecting other applications or users.
     */
    override fun setEnabled(enabled: Boolean)
    {
        if (enabled)
        {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, launchCommand)
        }
        else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME))
        {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }
    }

    companion object
    {
        private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        private const val VALUE_NAME = "ClearDictate"

        /**
         * Resolves the packaged executable used for this process so the startup entry remains valid regardless of installation folder.
         */
        fun forCurrentProcess(): WindowsDesktopStartupRegistration
        {
            val command = ProcessHandle.current().info().command().orElseThrow { IllegalStateException("ClearDictate could not resolve its executable path.") }
            return WindowsDesktopStartupRegistration(Path.of(command))
        }
    }
}
