package cc.uukanshu.data.update

/** Durable identity for a DownloadManager request that may outlive the app process. */
data class UpdateDownloadRecord(
    /** Exact release snapshot used when the request was enqueued. */
    val info: UpdateInfo,
    /** Null between writing the intent to enqueue and recording the returned id. */
    val downloadId: Long? = null,
) {
    /**
     * Whether [other] describes the same DownloadManager request. Identity is
     * the request id when both sides have one, else the release identity
     * (tag/url/name). The full [UpdateInfo] is deliberately not compared: a
     * mid-flight re-check can refresh `size`/`sha256` on the dialog's copy, and
     * structural equality would then silently keep the stale record forever.
     */
    fun sameRequestAs(other: UpdateDownloadRecord): Boolean {
        if (downloadId != null && other.downloadId != null) return downloadId == other.downloadId
        return info.tag == other.info.tag &&
            info.apkUrl == other.info.apkUrl &&
            info.apkName == other.info.apkName
    }
}
