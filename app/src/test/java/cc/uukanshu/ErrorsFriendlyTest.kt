package cc.uukanshu

import cc.uukanshu.core.BookDeletedDuringDownloadException
import cc.uukanshu.core.CloudflareBlockedException
import cc.uukanshu.core.EmptyChapterListException
import cc.uukanshu.core.Errors
import cc.uukanshu.core.HttpStatusException
import cc.uukanshu.core.TocShrunkException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorsFriendlyTest {
    @Test fun mapsHttpCodesToChinese() {
        assertEquals("找不到內容（404），可能已被刪除", Errors.friendly(HttpStatusException(404, "https://uukanshu.cc/book/1/")))
        assertEquals("請求太頻繁，請稍後再試", Errors.friendly(HttpStatusException(429, "https://uukanshu.cc/")))
        assertEquals("伺服器忙碌，請稍後再試", Errors.friendly(HttpStatusException(500, "https://uukanshu.cc/")))
        assertEquals("網路逾時，請重試", Errors.friendly(HttpStatusException(408, "https://uukanshu.cc/")))
    }

    @Test fun mapsTypedFailures() {
        assertEquals("暫時被網站阻擋，請稍後再試或切換網路", Errors.friendly(CloudflareBlockedException()))
        assertEquals("章節列表為空，請稍後再試", Errors.friendly(EmptyChapterListException()))
        assertEquals("章節列表為空，請稍後再試", Errors.friendly(TocShrunkException(cached = 100, fresh = 3)))
        assertEquals("下載中書籍已被刪除", Errors.friendly(BookDeletedDuringDownloadException()))
    }

    @Test fun mapsNetworkExceptions() {
        assertEquals("網路連線失敗，請檢查網路後重試", Errors.friendly(UnknownHostException("uukanshu.cc")))
        assertEquals("網路連線失敗，請檢查網路後重試", Errors.friendly(SocketTimeoutException("timeout")))
    }

    @Test fun legacyStringFallbackStillHolds() {
        // Foreign/wrapped messages we don't construct (OkHttp internals) keep
        // mapping via substring/regex fallback. Our own old strings are NOT
        // covered here on purpose: their throw-sites are typed now, so asserting
        // the strings would re-couple the mapper to message text (see mapsTypedFailures).
        assertEquals("找不到內容（404），可能已被刪除", Errors.friendly(IOException("HTTP 404 for https://uukanshu.cc/book/1/")))
        assertEquals("網路連線失敗，請檢查網路後重試", Errors.friendly(IOException("Unable to resolve host \"uukanshu.cc\"")))
    }

    @Test fun stripsUrlsFromUnknownErrors() {
        val msg = Errors.friendly(IOException("failed to fetch https://uukanshu.cc/book/1/: timeout"))
        assertFalse("must not leak URLs: $msg", msg.contains("https://"))
    }

    @Test fun friendlyTextMapsDownloadReasons() {
        assertEquals("下載失敗，請重試", Errors.friendlyText("download failed (reason 404)"))
        assertEquals("下載任務已失效，請重新下載", Errors.friendlyText("download not found"))
    }
}
