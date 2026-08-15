package com.cleardictate.desktop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.min

/**
 * Draws a resolution-independent ClearDictate microphone that remains legible at Windows notification-area sizes.
 */
internal class DesktopTrayIconPainter : Painter()
{
    override val intrinsicSize = Size(64.0f, 64.0f)

    override fun DrawScope.onDraw()
    {
        val scale = min(size.width, size.height) / 64.0f
        val horizontalOffset = (size.width - (64.0f * scale)) / 2.0f
        val verticalOffset = (size.height - (64.0f * scale)) / 2.0f
        fun point(x: Float, y: Float) = Offset(horizontalOffset + (x * scale), verticalOffset + (y * scale))

        drawCircle(color = Color(0xFF5B42C3), radius = 30.0f * scale, center = point(32.0f, 32.0f))
        drawRoundRect(
            color = Color.White,
            topLeft = point(25.0f, 12.0f),
            size = Size(14.0f * scale, 27.0f * scale),
            cornerRadius = CornerRadius(7.0f * scale, 7.0f * scale)
        )

        val microphoneCradle = Path().apply {
            moveTo(point(19.0f, 30.0f).x, point(19.0f, 30.0f).y)
            lineTo(point(19.0f, 32.0f).x, point(19.0f, 32.0f).y)
            cubicTo(point(19.0f, 39.2f).x, point(19.0f, 39.2f).y, point(24.8f, 45.0f).x, point(24.8f, 45.0f).y, point(32.0f, 45.0f).x, point(32.0f, 45.0f).y)
            cubicTo(point(39.2f, 45.0f).x, point(39.2f, 45.0f).y, point(45.0f, 39.2f).x, point(45.0f, 39.2f).y, point(45.0f, 32.0f).x, point(45.0f, 32.0f).y)
            lineTo(point(45.0f, 30.0f).x, point(45.0f, 30.0f).y)
        }
        val microphoneStroke = Stroke(width = 5.0f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(microphoneCradle, Color.White, style = microphoneStroke)
        drawLine(Color.White, point(32.0f, 45.0f), point(32.0f, 54.0f), strokeWidth = 5.0f * scale, cap = StrokeCap.Round)
        drawLine(Color.White, point(24.0f, 54.0f), point(40.0f, 54.0f), strokeWidth = 5.0f * scale, cap = StrokeCap.Round)
    }
}
