package cc.uukanshu.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("uukanshu")

object PrefsKeys {
    val SIMPLIFIED = booleanPreferencesKey("simplified")
    val FONT_SCALE = floatPreferencesKey("font_scale")
    val THEME = stringPreferencesKey("theme")
    val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
}

class Prefs(private val context: Context) {
    companion object {
        const val SYSTEM = "system"
        const val LIGHT = "light"
        const val DARK = "dark"

        /** Cycle order for the theme toggle. */
        fun next(current: String): String = when (current) {
            SYSTEM -> LIGHT
            LIGHT -> DARK
            else -> SYSTEM
        }
    }

    val simplified: Flow<Boolean> =
        context.store.data.map { it[PrefsKeys.SIMPLIFIED] ?: false }
    val fontScale: Flow<Float> =
        context.store.data.map { it[PrefsKeys.FONT_SCALE] ?: 1f }

    suspend fun setSimplified(v: Boolean) {
        context.store.edit { it[PrefsKeys.SIMPLIFIED] = v }
    }

    suspend fun setFontScale(v: Float) {
        context.store.edit { it[PrefsKeys.FONT_SCALE] = v.coerceIn(0.8f, 1.6f) }
    }

    /** Theme mode: [SYSTEM] (follow system), [LIGHT], or [DARK]. */
    val theme: Flow<String> =
        context.store.data.map { it[PrefsKeys.THEME] ?: SYSTEM }

    suspend fun setTheme(v: String) {
        context.store.edit { it[PrefsKeys.THEME] = v }
    }

    /** Last GitHub update-check timestamp (epoch millis, 0 = never). */
    val lastUpdateCheck: Flow<Long> =
        context.store.data.map { it[PrefsKeys.LAST_UPDATE_CHECK] ?: 0L }

    suspend fun setLastUpdateCheck(now: Long) {
        context.store.edit { it[PrefsKeys.LAST_UPDATE_CHECK] = now }
    }

    /** Remote version the user asked not to be reminded about again. */
    val skippedVersion: Flow<String?> =
        context.store.data.map { it[PrefsKeys.SKIPPED_VERSION] }

    suspend fun setSkippedVersion(v: String?) {
        context.store.edit {
            if (v == null) it.remove(PrefsKeys.SKIPPED_VERSION)
            else it[PrefsKeys.SKIPPED_VERSION] = v
        }
    }
}
