package app.unscroll.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.unscroll.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR dessiné sur l'appareil (ZXing). Aucune image n'est envoyée, aucun compte Google.
 * Le jumelage se fait en montrant le code ou en collant le lien. Pas d'appareil photo.
 */
@Composable
fun SyncPairingSection(
    busy: Boolean,
    showPairing: Boolean,
    link: String?,
    paste: String,
    onToggle: () -> Unit,
    onPasteChange: (String) -> Unit,
    onPaste: () -> Unit,
    onCopied: () -> Unit,
    onCopyFailed: () -> Unit,
) {
    val context = LocalContext.current
    Text(
        stringResource(R.string.privacy_sync_pair_warning),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    OutlinedButton(onClick = onToggle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                if (showPairing) R.string.privacy_sync_pair_hide else R.string.privacy_sync_pair,
            ),
        )
    }
    if (showPairing && link != null) {
        val image = remember(link) { QrBitmaps.encode(link) }
        if (image != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = stringResource(R.string.privacy_sync_qr_cd),
                    modifier = Modifier.size(220.dp),
                )
            }
        } else {
            Text(
                stringResource(R.string.privacy_sync_qr_too_big),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            OutlinedTextField(
                value = link,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.privacy_sync_pair_link)) },
                readOnly = true,
                enabled = !busy,
            )
        }
        OutlinedButton(
            onClick = { copyLink(context, link, onCopied, onCopyFailed) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.privacy_sync_pair_copy))
        }
    }
    OutlinedTextField(
        value = paste,
        onValueChange = onPasteChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.privacy_sync_paste)) },
        supportingText = { Text(stringResource(R.string.privacy_sync_paste_help)) },
        enabled = !busy,
    )
    OutlinedButton(
        onClick = onPaste,
        enabled = !busy && paste.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.privacy_sync_paste_action))
    }
}


private fun copyLink(context: Context, link: String, onCopied: () -> Unit, onCopyFailed: () -> Unit) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clipboard == null) {
        onCopyFailed()
        return
    }
    val label = context.getString(R.string.privacy_sync_pair_link)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, link))
    onCopied()
}

private object QrBitmaps {
    fun encode(link: String): Bitmap? {
        matrix(link, ErrorCorrectionLevel.M)?.let { return it }
        return matrix(link, ErrorCorrectionLevel.L)
    }

    private fun matrix(link: String, level: ErrorCorrectionLevel): Bitmap? {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to level,
        )
        val code = try {
            QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 280, 280, hints)
        } catch (_: WriterException) {
            return null
        }
        val width = code.width
        val height = code.height
        val pixels = IntArray(width * height)
        val dark = 0xFF142226.toInt()
        val light = 0xFFFFFFFF.toInt()
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (code.get(x, y)) dark else light
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
