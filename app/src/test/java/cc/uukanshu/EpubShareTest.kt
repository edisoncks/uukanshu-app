package cc.uukanshu

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.export.EpubExporter
import cc.uukanshu.ui.detail.EPUB_MIME
import cc.uukanshu.ui.detail.buildEpubShareIntent
import cc.uukanshu.ui.detail.epubShareUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Share payload and the provider path that must match EpubExporter's cache dir. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class EpubShareTest {
    @Test fun intentCarriesTheStreamTypeAndReadGrant() {
        val uri = Uri.parse("content://cc.uukanshu.fileprovider/epub/sample.epub")
        val intent = buildEpubShareIntent(uri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(EPUB_MIME, intent.type)
        assertEquals("application/epub+zip", intent.type)
        assertEquals(
            uri,
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
        )
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertNotNull(intent.clipData)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }

    @Test fun providerExposesTheCacheExportDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, EpubExporter.DIR_NAME).apply { mkdirs() }
        val file = File(dir, "sample.epub").apply { writeText("x") }

        val uri = epubShareUri(context, file)

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertTrue(uri.path.orEmpty().contains(EpubExporter.DIR_NAME))
    }
}
