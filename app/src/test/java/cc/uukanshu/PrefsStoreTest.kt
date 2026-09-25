package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.prefs.PrefsKeys
import cc.uukanshu.data.update.UpdateDownloadRecord
import cc.uukanshu.data.update.UpdateInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PrefsStoreTest {
    // JUnit builds a fresh test instance per method, so this cache is per test:
    // one Prefs per sandbox. Two Prefs in one method would open two DataStores
    // for the same no-backup file, which DataStore forbids.
    private var cachedPrefs: Prefs? = null
    private fun prefs(): Prefs =
        cachedPrefs ?: Prefs(ApplicationProvider.getApplicationContext()).also { cachedPrefs = it }

    @Before fun resetToDefaults() = kotlinx.coroutines.runBlocking {
        // The main DataStore file is a process singleton: reset it first.
        val p = prefs()
        p.setSimplified(false)
        p.setFontScale(Prefs.FONT_DEFAULT)
        p.setTheme(Prefs.SYSTEM)
        p.setAutoBookCheckEnabled(true)
        p.setUpdateDownloadRecord(null)
    }

    @Test fun defaultsAreTraditionalSystem() = runTest {
        val p = prefs()
        assertEquals(false, p.simplified.first())
        assertEquals(1f, p.fontScale.first())
        assertEquals(Prefs.SYSTEM, p.theme.first())
    }

    @Test fun fontWriteClampsToBounds() = runTest {
        val p = prefs()
        p.setFontScale(99f)
        assertEquals(Prefs.FONT_MAX, p.fontScale.first())
        p.setFontScale(-1f)
        assertEquals(Prefs.FONT_MIN, p.fontScale.first())
    }

    @Test fun simplifiedRoundtrip() = runTest {
        val p = prefs()
        p.setSimplified(true)
        assertEquals(true, p.simplified.first())
        p.setSimplified(false)
        assertEquals(false, p.simplified.first())
    }

    @Test fun themeRoundtrip() = runTest {
        val p = prefs()
        p.setTheme(Prefs.DARK)
        assertEquals(Prefs.DARK, p.theme.first())
    }

    @Test fun themeWriteNormalizesUnknown() = runTest {
        val p = prefs()
        p.setTheme("dark-mode")
        assertEquals(Prefs.SYSTEM, p.theme.first())
    }

    @Test fun autoBookCheckKeyStaysLegacy() {
        // Changing the persisted string would silently reset every install (no migration).
        assertEquals("bg_check_enabled", PrefsKeys.AUTO_BOOK_CHECK_ENABLED.name)
    }

    @Test fun autoBookCheckRoundtrip() = runTest {
        val p = prefs()
        p.setAutoBookCheckEnabled(false)
        assertEquals(false, p.autoBookCheckEnabled.first())
        p.setAutoBookCheckEnabled(true)
        assertEquals(true, p.autoBookCheckEnabled.first())
    }

    @Test fun updateDownloadRecordRoundtrip() = runTest {
        val p = prefs()
        val record = UpdateDownloadRecord(
            info = UpdateInfo(
                tag = "v2.0.0",
                version = "2.0.0",
                changelog = "notes\nline two",
                apkUrl = "https://example.com/uukanshu-2.0.0.apk",
                apkName = "uukanshu-2.0.0.apk",
                htmlUrl = "https://example.com/releases/2.0.0",
                size = 123L,
                sha256 = "a".repeat(64),
            ),
            downloadId = 42L,
        )
        p.setUpdateDownloadRecord(record)
        assertEquals(record, p.updateDownloadRecord.first())
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertTrue(java.io.File(app.noBackupFilesDir, "uukanshu-update-download.preferences_pb").exists())
        val pending = UpdateDownloadRecord(record.info.copy(size = null, sha256 = null))
        p.setUpdateDownloadRecord(pending)
        assertEquals(pending, p.updateDownloadRecord.first())
        p.setUpdateDownloadRecord(null)
        assertEquals(null, p.updateDownloadRecord.first())
    }

    @Test fun clearUpdateDownloadRecordOnlyClearsMatchingRequest() = runTest {
        val p = prefs()
        val record = UpdateDownloadRecord(
            info = UpdateInfo(
                tag = "v2.0.0",
                version = "2.0.0",
                changelog = "notes",
                apkUrl = "https://example.com/uukanshu-2.0.0.apk",
                apkName = "uukanshu-2.0.0.apk",
                htmlUrl = "https://example.com/releases/2.0.0",
            ),
            downloadId = 42L,
        )
        p.setUpdateDownloadRecord(record)

        // A different release must not clobber the stored request.
        p.clearUpdateDownloadRecord(
            UpdateDownloadRecord(record.info.copy(tag = "v3.0.0", version = "3.0.0")),
        )
        assertEquals(record, p.updateDownloadRecord.first())

        // The same request (id-less dialog copy) clears it.
        p.clearUpdateDownloadRecord(UpdateDownloadRecord(record.info))
        assertEquals(null, p.updateDownloadRecord.first())
    }
}
