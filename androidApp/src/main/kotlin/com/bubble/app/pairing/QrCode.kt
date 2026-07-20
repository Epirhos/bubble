package com.bubble.app.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Génère le QR d'invitation localement (ZXing, aucun réseau, aucun stockage cloud —
 * contrainte security-by-design). Le contenu est le payload `bubble1:coupleId:pubKey`.
 */
@Composable
fun QrCodeImage(payload: String, sizePx: Int = 720, modifier: Modifier = Modifier) {
    val bitmap = remember(payload, sizePx) { encodeQr(payload, sizePx) }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Invitation", modifier = modifier)
}

private fun encodeQr(payload: String, size: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.WHITE else Color.TRANSPARENT)
        }
    }
    return bitmap
}
