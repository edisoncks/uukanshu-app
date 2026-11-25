package cc.uukanshu.ui.detail

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal const val EPUB_MIME = "application/epub+zip"

/**
 * Pure share payload: MIME, stream, and a read grant. ClipData carries the
 * same URI so receivers that only inspect the clip still get the grant.
 */
internal fun buildEpubShareIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = EPUB_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("epub", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/**
 * Exposes only the exporter's cache subdirectory (see res/xml/filepaths.xml).
 * A provider path mismatch throws here and is surfaced by the caller.
 */
internal fun epubShareUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Hands the finished file to the system chooser; the caller catches failures. */
internal fun shareEpub(context: Context, file: File, chooserTitle: String?) {
    val send = buildEpubShareIntent(epubShareUri(context, file))
    context.startActivity(Intent.createChooser(send, chooserTitle))
}
