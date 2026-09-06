package cc.uukanshu.data.net

/**
 * Network gateway for uukanshu.cc HTML fetches.
 *
 * Extracted from [SiteApi] so [cc.uukanshu.data.repo.BookRepo] depends on
 * this narrow contract instead of the concrete OkHttp client. JVM tests
 * fake this with canned HTML; production wires [SiteApi].
 */
interface SiteGateway {
    suspend fun get(url: String): String
    suspend fun search(keyword: String): String
}
