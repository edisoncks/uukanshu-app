package cc.uukanshu.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.uukanshu.data.update.UpdateDownloadRecord
import cc.uukanshu.data.update.UpdateInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.store by preferencesDataStore("uukanshu")

object PrefsKeys {
    val SIMPLIFIED = booleanPreferencesKey("simplified")
    val FONT_SCALE = floatPreferencesKey("font_scale")
    val THEME = stringPreferencesKey("theme")
    val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
    // Persisted string stays "bg_check_enabled": renaming it would silently
    // reset every existing install (no migration). Pinned by PrefsStoreTest.
    val AUTO_BOOK_CHECK_ENABLED = booleanPreferencesKey("bg_check_enabled")
    val LAST_BOOK_CHECK = longPreferencesKey("last_book_check")
    val UPDATE_DOWNLOAD_TAG = stringPreferencesKey("update_download_tag")
    val UPDATE_DOWNLOAD_VERSION = stringPreferencesKey("update_download_version")
    val UPDATE_DOWNLOAD_CHANGELOG = stringPreferencesKey("update_download_changelog")
    val UPDATE_DOWNLOAD_URL = stringPreferencesKey("update_download_url")
    val UPDATE_DOWNLOAD_NAME = stringPreferencesKey("update_download_name")
    val UPDATE_DOWNLOAD_HTML_URL = stringPreferencesKey("update_download_html_url")
    val UPDATE_DOWNLOAD_SIZE = longPreferencesKey("update_download_size")
    val UPDATE_DOWNLOAD_SHA256 = stringPreferencesKey("update_download_sha256")
    val UPDATE_DOWNLOAD_ID = longPreferencesKey("update_download_id")
}

class Prefs(private val context: Context) : cc.uukanshu.di.PrefsApi {
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

    override val simplified: Flow<Boolean> =
        context.store.data.map { it[PrefsKeys.SIMPLIFIED] ?: false }
    override val fontScale: Flow<Float> =
        context.store.data.map { coerceFontScale(it[PrefsKeys.FONT_SCALE] ?: FONT_DEFAULT) }

    override suspend fun setSimplified(v: Boolean) {
        context.store.edit { it[PrefsKeys.SIMPLIFIED] = v }
    }

    override suspend fun setFontScale(v: Float) {
        context.store.edit { it[PrefsKeys.FONT_SCALE] = coerceFontScale(v) }
    }

    /** Theme mode: [SYSTEM] (follow system), [LIGHT], or [DARK]. Unknown stored values read as SYSTEM. */
    override val theme: Flow<String> =
        context.store.data.map { normalizeTheme(it[PrefsKeys.THEME]) }

    override suspend fun setTheme(v: String) {
        // Normalize on write (mirrors setFontScale clamping): unknown values
        // must not persist in DataStore just because the read path tolerates them.
        context.store.edit { it[PrefsKeys.THEME] = normalizeTheme(v) }
    }

    /** Last GitHub update-check timestamp (epoch millis, 0 = never). */
    override val lastUpdateCheck: Flow<Long> =
        context.store.data.map { it[PrefsKeys.LAST_UPDATE_CHECK] ?: 0L }

    /**
     * DownloadManager ids are device-local and must never be restored by
     * cloud/device backup, unlike the main `uukanshu` store. `Prefs` is an app
     * singleton, so one lazily-created store per process is enough — DataStore
     * forbids a second active instance for the same file.
     */
    private val updateDownloadStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create {
            File(
                context.applicationContext.noBackupFilesDir,
                "uukanshu-update-download.preferences_pb",
            )
        }
    }

    override val updateDownloadRecord: Flow<UpdateDownloadRecord?> =
        updateDownloadStore.data.map { it.toUpdateDownloadRecord() }

    /** Decode the record from a preferences snapshot; null unless every required key is present. */
    private fun Preferences.toUpdateDownloadRecord(): UpdateDownloadRecord? {
        val tag = this[PrefsKeys.UPDATE_DOWNLOAD_TAG] ?: return null
        val version = this[PrefsKeys.UPDATE_DOWNLOAD_VERSION] ?: return null
        val changelog = this[PrefsKeys.UPDATE_DOWNLOAD_CHANGELOG] ?: return null
        val url = this[PrefsKeys.UPDATE_DOWNLOAD_URL] ?: return null
        val name = this[PrefsKeys.UPDATE_DOWNLOAD_NAME] ?: return null
        val htmlUrl = this[PrefsKeys.UPDATE_DOWNLOAD_HTML_URL] ?: return null
        return UpdateDownloadRecord(
            info = UpdateInfo(
                tag = tag,
                version = version,
                changelog = changelog,
                apkUrl = url,
                apkName = name,
                htmlUrl = htmlUrl,
                size = this[PrefsKeys.UPDATE_DOWNLOAD_SIZE],
                sha256 = this[PrefsKeys.UPDATE_DOWNLOAD_SHA256],
            ),
            downloadId = this[PrefsKeys.UPDATE_DOWNLOAD_ID],
        )
    }

    override suspend fun setLastUpdateCheck(now: Long) {
        context.store.edit { it[PrefsKeys.LAST_UPDATE_CHECK] = now }
    }

    /** Remote version the user asked not to be reminded about again. */
    override val skippedVersion: Flow<String?> =
        context.store.data.map { it[PrefsKeys.SKIPPED_VERSION] }

    /** Automatic 追更 checks enabled (default true). Gates every automatic
     *  path (Worker + shelf fallback); manual check unaffected. */
    override val autoBookCheckEnabled: Flow<Boolean> =
        context.store.data.map { it[PrefsKeys.AUTO_BOOK_CHECK_ENABLED] ?: true }

    override suspend fun setAutoBookCheckEnabled(v: Boolean) {
        context.store.edit { it[PrefsKeys.AUTO_BOOK_CHECK_ENABLED] = v }
    }

    /** Last 追更 check (foreground or background, epoch millis, 0 = never). */
    override val lastBookCheck: Flow<Long> =
        context.store.data.map { it[PrefsKeys.LAST_BOOK_CHECK] ?: 0L }

    override suspend fun setLastBookCheck(now: Long) {
        context.store.edit { it[PrefsKeys.LAST_BOOK_CHECK] = now }
    }

    override suspend fun setSkippedVersion(v: String?) {
        context.store.edit {
            if (v == null) it.remove(PrefsKeys.SKIPPED_VERSION)
            else it[PrefsKeys.SKIPPED_VERSION] = v
        }
    }

    override suspend fun setUpdateDownloadRecord(record: UpdateDownloadRecord?) {
        updateDownloadStore.edit { values ->
            if (record == null) {
                values.clearUpdateDownloadFields()
            } else {
                val info = record.info
                values[PrefsKeys.UPDATE_DOWNLOAD_TAG] = info.tag
                values[PrefsKeys.UPDATE_DOWNLOAD_VERSION] = info.version
                values[PrefsKeys.UPDATE_DOWNLOAD_CHANGELOG] = info.changelog
                values[PrefsKeys.UPDATE_DOWNLOAD_URL] = info.apkUrl
                values[PrefsKeys.UPDATE_DOWNLOAD_NAME] = info.apkName
                values[PrefsKeys.UPDATE_DOWNLOAD_HTML_URL] = info.htmlUrl
                if (info.size == null) values.remove(PrefsKeys.UPDATE_DOWNLOAD_SIZE)
                else values[PrefsKeys.UPDATE_DOWNLOAD_SIZE] = info.size
                if (info.sha256 == null) values.remove(PrefsKeys.UPDATE_DOWNLOAD_SHA256)
                else values[PrefsKeys.UPDATE_DOWNLOAD_SHA256] = info.sha256
                if (record.downloadId == null) values.remove(PrefsKeys.UPDATE_DOWNLOAD_ID)
                else values[PrefsKeys.UPDATE_DOWNLOAD_ID] = record.downloadId
            }
        }
    }

    override suspend fun clearUpdateDownloadRecord(expected: UpdateDownloadRecord) {
        // Read and conditional remove share one edit transaction: a record
        // written by a concurrent path (e.g. a fresh reattach) is never
        // clobbered by a clear that raced it.
        updateDownloadStore.edit { values ->
            val current = values.toUpdateDownloadRecord()
            if (current == null || current.sameRequestAs(expected)) {
                values.clearUpdateDownloadFields()
            }
        }
    }

    private fun MutablePreferences.clearUpdateDownloadFields() {
        remove(PrefsKeys.UPDATE_DOWNLOAD_TAG)
        remove(PrefsKeys.UPDATE_DOWNLOAD_VERSION)
        remove(PrefsKeys.UPDATE_DOWNLOAD_CHANGELOG)
        remove(PrefsKeys.UPDATE_DOWNLOAD_URL)
        remove(PrefsKeys.UPDATE_DOWNLOAD_NAME)
        remove(PrefsKeys.UPDATE_DOWNLOAD_HTML_URL)
        remove(PrefsKeys.UPDATE_DOWNLOAD_SIZE)
        remove(PrefsKeys.UPDATE_DOWNLOAD_SHA256)
        remove(PrefsKeys.UPDATE_DOWNLOAD_ID)
    }
}
