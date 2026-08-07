package com.cleardictate.desktop.inference

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the perceptual microphone meter mapping independently of Windows capture timing.
 */
class MicrophoneActivityTest
{
    @Test
    fun `silence remains empty and audible peaks remain visible`()
    {
        assertEquals(0.0F, calculateMicrophoneActivity(floatArrayOf(0.0F, 0.0F)))
        assertEquals(0.5F, calculateMicrophoneActivity(floatArrayOf(-0.25F, 0.1F)))
        assertEquals(1.0F, calculateMicrophoneActivity(floatArrayOf(-1.0F, 0.5F)))
    }
}
