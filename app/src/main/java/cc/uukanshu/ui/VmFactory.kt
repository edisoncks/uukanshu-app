package cc.uukanshu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Single ViewModel factory helper for the whole app.
 *
 * Every screen used to hand-write its own anonymous
 * `object : ViewModelProvider.Factory` with an unchecked cast. Six copies
 * meant six chances to wire the wrong dependency (a fresh `Prefs(app)`
 * here, a missing `downloadManager` there) with no compiler help.
 *
 * All ViewModels go through this one function so construction stays
 * uniform and greppable; dependency *instances* come from [cc.uukanshu.App]
 * singletons (see `App.prefs` / `App.t2s` / `App.repo`), never `new`
 * per-screen.
 */
inline fun <reified VM : ViewModel> vmFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
