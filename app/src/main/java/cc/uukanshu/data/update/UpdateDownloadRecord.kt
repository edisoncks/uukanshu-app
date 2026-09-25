package cc.uukanshu.data.update

/** Durable identity for a DownloadManager request that may outlive the app process. */
data class UpdateDownloadRecord(
    /** Exact release snapshot used when the request was enqueued. */
    val info: UpdateInfo,
    /** Null between writing the intent to enqueue and recording the returned id. */
    val downloadId: Long? = null,
)
