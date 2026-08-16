package com.cleardictate.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Specifies the gesture and screen-positioning rules shared by the floating microphone and Undo controls.
 */
class FloatingControlDragTrackerTest
{
    @Test
    fun `movement inside the system touch threshold remains a dictation hold`()
    {
        val tracker = FloatingControlDragTracker(touchSlop = 12)
        tracker.start(pointerX = 100.0f, pointerY = 200.0f, controlPosition = FloatingControlPosition(900, 700))

        assertNull(tracker.move(pointerX = 107.0f, pointerY = 208.0f))
        assertFalse(tracker.isDragging)
        assertFalse(tracker.finish())
    }

    @Test
    fun `movement beyond the system touch threshold repositions from the original control location`()
    {
        val tracker = FloatingControlDragTracker(touchSlop = 12)
        tracker.start(pointerX = 100.0f, pointerY = 200.0f, controlPosition = FloatingControlPosition(900, 700))

        assertEquals(FloatingControlPosition(950, 740), tracker.move(pointerX = 150.0f, pointerY = 240.0f))
        assertTrue(tracker.isDragging)
        assertTrue(tracker.finish())
    }

    @Test
    fun `movement after the hold commits to dictation cannot reposition or cancel it`()
    {
        val tracker = FloatingControlDragTracker(touchSlop = 12)
        tracker.start(pointerX = 100.0f, pointerY = 200.0f, controlPosition = FloatingControlPosition(900, 700))
        tracker.lockForDictation()

        assertNull(tracker.move(pointerX = 180.0f, pointerY = 260.0f))
        assertFalse(tracker.isDragging)
        assertFalse(tracker.finish())
    }

    @Test
    fun `control position is clamped fully inside the display`()
    {
        assertEquals(
            FloatingControlPosition(1012, 2332),
            clampFloatingControlPosition(FloatingControlPosition(1200, 2500), displayWidth = 1080, displayHeight = 2400, controlSize = 68)
        )
        assertEquals(
            FloatingControlPosition(0, 0),
            clampFloatingControlPosition(FloatingControlPosition(-40, -80), displayWidth = 1080, displayHeight = 2400, controlSize = 68)
        )
    }

    @Test
    fun `undo control is centered immediately beneath the microphone when it fits`()
    {
        assertEquals(
            FloatingControlPosition(508, 768),
            calculateFloatingUndoControlPosition(
                microphonePosition = FloatingControlPosition(500, 700),
                displayWidth = 1080,
                displayHeight = 2400,
                microphoneSize = 68,
                undoSize = 52
            )
        )
    }

    @Test
    fun `undo control is centered immediately above the microphone when it cannot fit beneath`()
    {
        assertEquals(
            FloatingControlPosition(1008, 2280),
            calculateFloatingUndoControlPosition(
                microphonePosition = FloatingControlPosition(1000, 2332),
                displayWidth = 1080,
                displayHeight = 2400,
                microphoneSize = 68,
                undoSize = 52
            )
        )
    }
}
