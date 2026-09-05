package cc.uukanshu.ui

import androidx.navigation.NavController

/**
 * Single source of truth for navigation routes.
 *
 * String routes were copy-pasted across `MainActivity` (`"detail/$id"`,
 * `"reader/$id/$pos"`, tab names). A typo compiles but crashes at runtime,
 * and the duplicate-suppression rules (single-top tabs, one reader per
 * book, skip identical double-tap) lived inline as comments. All of that
 * now lives here so mis-navigation is a compile error, not a runtime one.
 */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    const val DETAIL_PATTERN = "detail/{bookId}"
    const val READER_PATTERN = "reader/{bookId}/{position}/{pageId}"

    fun detail(bookId: String): String = "detail/$bookId"

    /**
     * Chapter route carries both display order ([position]) and stable
     * identity ([pageId]). Position alone shifts when the site inserts
     * chapters between Detail and Reader; the reader resolves by pageId
     * first so a shifted TOC cannot open the wrong chapter.
     */
    fun reader(bookId: String, position: Int, pageId: Long = 0L): String =
        "reader/$bookId/$position/$pageId"

    /** Detail destinations pop readers back to this (reader only opens from detail). */
    fun detailBase(bookId: String): String = detail(bookId)
}

/** Tab navigation: single-top + state restore, never stacks duplicate tabs. */
fun NavController.navigateToTab(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(graph.startDestinationId) { saveState = true }
        restoreState = true
    }
}

/** Book open from any tab: rapid double-taps must not stack duplicates. */
fun NavController.navigateToBook(bookId: String) {
    navigate(Routes.detail(bookId)) { launchSingleTop = true }
}

/**
 * Chapter open from detail: one reader per book (opening another chapter
 * pops the previous reader), identical double-tap skipped entirely.
 * Carries stable pageId so a TOC shift between tap and open cannot alias
 * to a neighbor: the reader prefers pageId over position.
 *
 * Reads the controller property directly — the collected bottom-bar entry
 * lags a frame and would reintroduce the race. Clicks serialize on Main
 * and navigate() commits synchronously, so check-then-navigate here is
 * atomic w.r.t. any other tap.
 */
fun NavController.navigateToChapter(bookId: String, position: Int, pageId: Long = 0L) {
    val top = currentBackStackEntry
    val same = top?.destination?.route == Routes.READER_PATTERN &&
        top.arguments?.getString("bookId") == bookId &&
        top.arguments?.getInt("position") == position &&
        top.arguments?.getLong("pageId") == pageId
    if (!same) {
        navigate(Routes.reader(bookId, position, pageId)) {
            launchSingleTop = true
            popUpTo(Routes.detailBase(bookId)) { inclusive = false }
        }
    }
}
