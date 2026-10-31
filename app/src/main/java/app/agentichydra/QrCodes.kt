package app.agentichydra

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR drawing and reading for the settings transfer. Codes are painted with theme tokens so they
 * stay legible in light and dark mode, and a scan is decoded from a downscaled camera frame —
 * these codes hold little data, so they never need the full camera resolution.
 */
private const val QR_QUIET_ZONE = 2
private const val MAX_DECODE_DIMENSION = 640

/** Squared code at `sizePx`, drawn as [modules] on a [field] background. */
internal fun qrBitmap(
    content: String,
    sizePx: Int,
    field: Color,
    modules: Color,
): Bitmap {
    val matrix =
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.MARGIN to QR_QUIET_ZONE,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )
    val width = matrix.width
    val height = matrix.height
    val dark = modules.toArgb()
    val light = field.toArgb()
    val pixels = IntArray(width * height) { index -> if (matrix.get(index % width, index / width)) dark else light }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/** The text of a QR code in this frame, or null when it holds none or cannot be read yet. */
internal fun decodeQrText(frame: Bitmap): String? {
    val divisor = maxOf(frame.width, frame.height) / MAX_DECODE_DIMENSION
    val scaled =
        if (divisor > 1) {
            Bitmap.createScaledBitmap(frame, frame.width / divisor, frame.height / divisor, true)
        } else {
            frame
        }
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    if (scaled !== frame) scaled.recycle()
    val source = RGBLuminanceSource(scaled.width, scaled.height, pixels)
    val hints =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
    return runCatching {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
