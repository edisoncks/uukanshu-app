package cc.uukanshu

import cc.uukanshu.core.Errors
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ErrorsFriendlyTest {
    @Test fun mapsHttpCodesToChinese() {
        assertEquals("找不到內容（404），可能已被刪除", Errors.friendly(IOException("HTTP 404 for https://uukanshu.cc/book/1/")))
        assertEquals("請求太頻繁，請稍後再試", Errors.friendly(IOException("HTTP 429 for https://uukanshu.cc/")))
        assertEquals("伺服器忙碌，請稍後再試", Errors.friendly(IOException("HTTP 500 for https://uukanshu.cc/")))
        assertEquals("網路逾時，請重試", Errors.friendly(IOException("HTTP 408 for https://uukanshu.cc/")))
    }

    @Test fun mapsNetworkFailures() {
        assertEquals("網路連線失敗，請檢查網路後重試", Errors.friendly(IOException("Unable to resolve host \"uukanshu.cc\"")))
        assertEquals("暫時被網站阻擋，請稍後再試或切換網路", Errors.friendly(IOException("blocked by Cloudflare — try again later")))
        assertEquals("章節列表為空，請稍後再試", Errors.friendly(IOException("empty chapter list — try again later")))
        assertEquals("章節列表為空，請稍後再試", Errors.friendly(IOException("chapter list shrank (3 < 100) — refusing to wipe cache")))
    }

    @Test fun stripsUrlsFromUnknownErrors() {
        val msg = Errors.friendly(IOException("failed to fetch https://uukanshu.cc/book/1/: timeout"))
        assertFalse("must not leak URLs: $msg", msg.contains("https://"))
    }

    @Test fun friendlyTextMapsDownloadReasons() {
        assertEquals("下載失敗，請重試", Errors.friendlyText("download failed (reason 404)"))
        assertEquals("下載任務已失效，請重新下載", Errors.friendlyText("download not found"))
    }

    @Test fun friendlyOrThrowRethrowsCancellation() {
        try {
            Errors.friendlyOrThrow(CancellationException("stop"))
            fail("must rethrow")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }
}
