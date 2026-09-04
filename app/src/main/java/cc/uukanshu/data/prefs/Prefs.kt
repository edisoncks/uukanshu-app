package cc.uukanshu.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("uukanshu")

object PrefsKeys {
    val SIMPLIFIED = booleanPreferencesKey("simplified")
    val FONT_SCALE = floatPreferencesKey("font_scale")
}

class Prefs(private val context: Context) {
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
}
