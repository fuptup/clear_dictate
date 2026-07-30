package com.cleardictate.android.models

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Proves that worker cancellation reaches a blocking network source independently of its read.
 */
class DownloadCancellationMonitorTest
{
    @Test
    fun `cancellation disconnects the active download promptly`()
    {
        val cancellationRequested = AtomicBoolean(false)
        val cancellationDelivered = CountDownLatch(1)

        DownloadCancellationMonitor(
            cancellationRequested = cancellationRequested::get,
            cancelActiveDownload = cancellationDelivered::countDown,
            pollIntervalMilliseconds = 1L
        ).use {
            cancellationRequested.set(true)
            assertTrue(cancellationDelivered.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `normal completion does not cancel the download`()
    {
        val cancellationDelivered = AtomicBoolean(false)

        DownloadCancellationMonitor(
            cancellationRequested = { false },
            cancelActiveDownload = { cancellationDelivered.set(true) },
            pollIntervalMilliseconds = 1L
        ).close()

        assertFalse(cancellationDelivered.get())
    }
}
