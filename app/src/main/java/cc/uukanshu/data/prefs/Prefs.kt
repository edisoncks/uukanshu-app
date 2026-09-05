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

        /** Single source for font bounds: read-clamp, write-clamp, UI step must agree. */
        const val FONT_MIN = 0.8f
        const val FONT_MAX = 1.6f
        const val FONT_DEFAULT = 1f

        fun coerceFontScale(v: Float): Float = v.coerceIn(FONT_MIN, FONT_MAX)

        /** Normalize persisted/unknown theme strings to a known mode (fail-safe to SYSTEM). */
        fun normalizeTheme(v: String?): String = when (v) {
            LIGHT, DARK -> v
            else -> SYSTEM
        }

        /** Cycle order for the theme toggle. Unknown input restarts at SYSTEM. */
        fun next(current: String): String = when (normalizeTheme(current)) {
            SYSTEM -> LIGHT
            LIGHT -> DARK
            else -> SYSTEM
        }
    }

    val simplified: Flow<Boolean> =
        context.store.data.map { it[PrefsKeys.SIMPLIFIED] ?: false }
    val fontScale: Flow<Float> =
        context.store.data.map { coerceFontScale(it[PrefsKeys.FONT_SCALE] ?: FONT_DEFAULT) }

    suspend fun setSimplified(v: Boolean) {
        context.store.edit { it[PrefsKeys.SIMPLIFIED] = v }
    }

    suspend fun setFontScale(v: Float) {
        context.store.edit { it[PrefsKeys.FONT_SCALE] = coerceFontScale(v) }
    }

    /** Theme mode: [SYSTEM] (follow system), [LIGHT], or [DARK]. Unknown stored values read as SYSTEM. */
    val theme: Flow<String> =
        context.store.data.map { normalizeTheme(it[PrefsKeys.THEME]) }

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
