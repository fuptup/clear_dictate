package com.cleardictate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Renders the versioned phone-pairing text as a high-contrast QR image for the Android scanner.
 */
internal object DesktopPhonePairingQrCode
{
    private const val IMAGE_PIXEL_SIZE = 320

    /**
     * Uses a narrow quiet zone so the code remains large enough to scan inside the compact setup dialog.
     */
    fun render(payload: String): ImageBitmap
    {
        return renderBufferedImage(payload).toComposeImageBitmap()
    }

    /**
     * Keeps the encoded pixels independently testable before Compose scales them into the dialog.
     */
    internal fun renderBufferedImage(payload: String): BufferedImage
    {
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            IMAGE_PIXEL_SIZE,
            IMAGE_PIXEL_SIZE,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val image = BufferedImage(IMAGE_PIXEL_SIZE, IMAGE_PIXEL_SIZE, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until IMAGE_PIXEL_SIZE)
        {
            for (x in 0 until IMAGE_PIXEL_SIZE)
            {
                image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        return image
    }
}
