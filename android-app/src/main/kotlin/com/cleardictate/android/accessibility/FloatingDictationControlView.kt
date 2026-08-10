package com.cleardictate.android.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.cleardictate.android.R
import com.cleardictate.inference.service.R as InferenceServiceResources

/**
 * Names the four states the system-wide microphone must communicate without opening the app.
 */
internal enum class FloatingDictationVisualState
{
    UNAVAILABLE,
    READY,
    RECORDING,
    PROCESSING
}

/**
 * Renders the transient action that removes the most recent unchanged insertion.
 */
internal class FloatingUndoControlView(context: Context) : FrameLayout(context)
{
    init
    {
        isClickable = true
        isFocusable = false
        elevation = densityIndependentPixels(10).toFloat()
        setPadding(densityIndependentPixels(10), densityIndependentPixels(10), densityIndependentPixels(10), densityIndependentPixels(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF374151.toInt())
            setStroke(densityIndependentPixels(2), 0x66FFFFFF)
        }
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_cleardictate_undo)
                setColorFilter(Color.WHITE)
            },
            LayoutParams(densityIndependentPixels(28), densityIndependentPixels(28), Gravity.CENTER)
        )
        contentDescription = "Remove last dictation"
    }

    private fun densityIndependentPixels(value: Int): Int
    {
        return (value * resources.displayMetrics.density).toInt()
    }
}

/**
 * Renders a compact circular microphone, live input level, and unmistakable processing spinner.
 */
internal class FloatingDictationControlView(context: Context) : FrameLayout(context)
{
    private val microphone = ImageView(context)
    private val processingIndicator = ProgressBar(context)
    private val inputLevel = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)

    init
    {
        isClickable = true
        isFocusable = false
        elevation = densityIndependentPixels(10).toFloat()
        setPadding(densityIndependentPixels(14), densityIndependentPixels(14), densityIndependentPixels(14), densityIndependentPixels(10))

        microphone.setImageResource(InferenceServiceResources.drawable.ic_cleardictate_microphone)
        microphone.setColorFilter(Color.WHITE)
        addView(microphone, centeredLayoutParameters(32))

        processingIndicator.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        processingIndicator.visibility = View.GONE
        addView(processingIndicator, centeredLayoutParameters(34))

        inputLevel.max = 100
        inputLevel.progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        inputLevel.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
        addView(
            inputLevel,
            LayoutParams(densityIndependentPixels(42), densityIndependentPixels(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )

        update(FloatingDictationVisualState.UNAVAILABLE, 0.0f)
    }

    /**
     * Changes both color and icon treatment so recording and remote processing remain visually distinct.
     */
    fun update(state: FloatingDictationVisualState, normalizedAudioLevel: Float)
    {
        background = circularBackground(
            when (state)
            {
                FloatingDictationVisualState.UNAVAILABLE -> 0xFF6B7280.toInt()
                FloatingDictationVisualState.READY -> 0xFF5B42C3.toInt()
                FloatingDictationVisualState.RECORDING -> 0xFFD13C4B.toInt()
                FloatingDictationVisualState.PROCESSING -> 0xFFCC7A00.toInt()
            }
        )
        processingIndicator.visibility = if (state == FloatingDictationVisualState.PROCESSING) View.VISIBLE else View.GONE
        microphone.visibility = if (state == FloatingDictationVisualState.PROCESSING) View.GONE else View.VISIBLE
        inputLevel.visibility = if (state == FloatingDictationVisualState.RECORDING) View.VISIBLE else View.INVISIBLE
        inputLevel.progress = (normalizedAudioLevel.coerceIn(0.0f, 1.0f) * 100).toInt()
        contentDescription = when (state)
        {
            FloatingDictationVisualState.UNAVAILABLE -> "ClearDictate unavailable"
            FloatingDictationVisualState.READY -> "Hold to dictate"
            FloatingDictationVisualState.RECORDING -> "Recording; release to process"
            FloatingDictationVisualState.PROCESSING -> "Transcribing and polishing on the PC"
        }
    }

    private fun centeredLayoutParameters(size: Int): LayoutParams
    {
        return LayoutParams(densityIndependentPixels(size), densityIndependentPixels(size), Gravity.CENTER)
    }

    private fun circularBackground(color: Int): GradientDrawable
    {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(densityIndependentPixels(2), 0x44FFFFFF)
        }
    }

    private fun densityIndependentPixels(value: Int): Int
    {
        return (value * resources.displayMetrics.density).toInt()
    }
}
