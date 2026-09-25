package cc.uukanshu

import android.app.DownloadManager
import cc.uukanshu.data.update.DownloadRequestMatcher
import cc.uukanshu.data.update.DownloadRequestRow
import cc.uukanshu.data.update.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRequestMatcherTest {
    private val info = UpdateInfo(
        tag = "v2.0.0",
        version = "2.0.0",
        changelog = "notes",
        apkUrl = "https://example.com/uukanshu-2.0.0.apk",
        apkName = "uukanshu-2.0.0.apk",
        htmlUrl = "https://example.com/releases/2.0.0",
    )

    @Test fun matchingRequestsPreferLiveWorkAndNewestId() {
        val rows = listOf(
            DownloadRequestRow(3L, info.apkUrl, info.apkName, "file:///tmp/${info.apkName}", DownloadManager.STATUS_SUCCESSFUL),
            DownloadRequestRow(2L, info.apkUrl, info.apkName, "file:///tmp/${info.apkName}", DownloadManager.STATUS_RUNNING),
            DownloadRequestRow(4L, info.apkUrl, info.apkName, null, DownloadManager.STATUS_PENDING),
        )

        assertEquals(listOf(4L, 2L, 3L), DownloadRequestMatcher.matching(rows, info).map { it.id })
    }

    @Test fun unrelatedAssetOrDestinationIsNotReattached() {
        val rows = listOf(
            DownloadRequestRow(1L, "https://example.com/other.apk", info.apkName, null, DownloadManager.STATUS_RUNNING),
            DownloadRequestRow(2L, info.apkUrl, "uukanshu-other.apk", null, DownloadManager.STATUS_RUNNING),
            DownloadRequestRow(3L, info.apkUrl, info.apkName, "file:///tmp/other.apk", DownloadManager.STATUS_RUNNING),
            DownloadRequestRow(4L, info.apkUrl, info.apkName, null, DownloadManager.STATUS_FAILED),
        )

        assertEquals(emptyList<Long>(), DownloadRequestMatcher.matching(rows, info).map { it.id })
    }
}
