package com.cleardictate.android.accessibility

import android.content.Context
import kotlin.math.roundToInt

/**
 * Identifies the floating microphone's top-left position in physical display pixels.
 */
internal data class FloatingControlPosition(val x: Int, val y: Int)

/**
 * Separates ordinary finger jitter from an intentional drag using Android's system-provided touch threshold.
 */
internal class FloatingControlDragTracker(private val touchSlop: Int)
{
    private var gestureStart: FloatingControlDragStart? = null
    private var dictationLocked = false

    var isDragging = false
        private set

    /**
     * Captures both the pointer and window origins so later updates remain stable even after the window starts moving.
     */
    fun start(pointerX: Float, pointerY: Float, controlPosition: FloatingControlPosition)
    {
        gestureStart = FloatingControlDragStart(pointerX, pointerY, controlPosition)
        dictationLocked = false
        isDragging = false
    }

    /**
     * Commits a stationary hold to dictation so later finger drift cannot reinterpret the active recording as a drag.
     */
    fun lockForDictation()
    {
        if (gestureStart != null && !isDragging)
        {
            dictationLocked = true
        }
    }

    /**
     * Returns a new window position only after movement passes the platform touch threshold.
     */
    fun move(pointerX: Float, pointerY: Float): FloatingControlPosition?
    {
        val start = gestureStart ?: return null
        if (dictationLocked)
        {
            return null
        }
        val movementX = pointerX - start.pointerX
        val movementY = pointerY - start.pointerY
        if (!isDragging && movementX * movementX + movementY * movementY <= touchSlop * touchSlop)
        {
            return null
        }
        isDragging = true
        return FloatingControlPosition(start.controlPosition.x + movementX.roundToInt(), start.controlPosition.y + movementY.roundToInt())
    }

    /**
     * Ends the gesture and reports whether it became an intentional drag.
     */
    fun finish(): Boolean
    {
        val finishedDragging = isDragging
        gestureStart = null
        dictationLocked = false
        isDragging = false
        return finishedDragging
    }
}

/**
 * Keeps the entire microphone hit target within the current display after arbitrary pointer movement.
 */
internal fun clampFloatingControlPosition(position: FloatingControlPosition, displayWidth: Int, displayHeight: Int, controlSize: Int): FloatingControlPosition
{
    return FloatingControlPosition(
        x = position.x.coerceIn(0, (displayWidth - controlSize).coerceAtLeast(0)),
        y = position.y.coerceIn(0, (displayHeight - controlSize).coerceAtLeast(0))
    )
}

/**
 * Persists the user's chosen microphone position within this app installation.
 */
internal class FloatingControlPositionStore(context: Context)
{
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Returns no position until the user has deliberately moved the microphone.
     */
    fun load(): FloatingControlPosition?
    {
        if (!preferences.contains(KEY_X) || !preferences.contains(KEY_Y))
        {
            return null
        }
        return FloatingControlPosition(preferences.getInt(KEY_X, 0), preferences.getInt(KEY_Y, 0))
    }

    /**
     * Saves only a completed drag so incidental or cancelled touches never change the remembered location.
     */
    fun save(position: FloatingControlPosition)
    {
        preferences.edit().putInt(KEY_X, position.x).putInt(KEY_Y, position.y).apply()
    }

    private companion object
    {
        private const val PREFERENCES_NAME = "floating_microphone_position"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}

/**
 * Holds immutable gesture origins while the overlay itself changes position.
 */
private data class FloatingControlDragStart(val pointerX: Float, val pointerY: Float, val controlPosition: FloatingControlPosition)
