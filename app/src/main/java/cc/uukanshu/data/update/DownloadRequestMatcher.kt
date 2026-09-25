package cc.uukanshu.data.update

import java.io.File
import java.net.URI

/** Small query projection kept pure so request recovery can be tested without DownloadManager. */
internal data class DownloadRequestRow(
    val id: Long,
    val source: String,
    val description: String?,
    val localUri: String?,
    val status: Int,
)

internal object DownloadRequestMatcher {
    /** Matching live requests take precedence over a prior completed request. */
    fun matching(rows: List<DownloadRequestRow>, info: UpdateInfo): List<DownloadRequestRow> =
        rows.asSequence()
            .filter { row ->
                row.source == info.apkUrl &&
                    row.description == info.apkName &&
                    localNameMatches(row.localUri, info.apkName) &&
                    isReusableStatus(row.status)
            }
            .sortedWith(
                compareBy<DownloadRequestRow> { if (isActiveStatus(it.status)) 0 else 1 }
                    .thenByDescending { it.id },
            )
            .toList()

    fun isActiveStatus(status: Int): Boolean =
        status == android.app.DownloadManager.STATUS_PENDING ||
            status == android.app.DownloadManager.STATUS_RUNNING ||
            status == android.app.DownloadManager.STATUS_PAUSED

    private fun isReusableStatus(status: Int): Boolean =
        isActiveStatus(status) || status == android.app.DownloadManager.STATUS_SUCCESSFUL

    private fun localNameMatches(localUri: String?, expectedName: String): Boolean {
        if (localUri.isNullOrBlank()) return true
        val parsed = runCatching { URI(localUri) }.getOrNull() ?: return false
        return parsed.scheme != "file" || File(parsed.path.orEmpty()).name == expectedName
    }
}
