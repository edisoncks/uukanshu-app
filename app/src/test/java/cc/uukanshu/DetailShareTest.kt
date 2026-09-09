package cc.uukanshu

import android.content.Intent
import cc.uukanshu.ui.detail.buildShareIntent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Share sheet payload is URL-only (see DetailScreen share button). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DetailShareTest {
    @Test fun shareIntentCarriesUrlAsPlainText() {
        val url = "https://uukanshu.cc/book/1/"
        val intent = buildShareIntent(url)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(url, intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
