package com.cleardictate.desktop

import com.cleardictate.inference.remote.PhonePairingPayload
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPhonePairingQrCodeTest
{
    @Test
    fun `rendered pairing code decodes to the exact versioned payload`()
    {
        val payload = PhonePairingPayload("http://192.168.1.20:8765", "private-token").encode()
        val image = DesktopPhonePairingQrCode.renderBufferedImage(payload)
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))

        assertEquals(payload, QRCodeReader().decode(bitmap).text)
    }
}
