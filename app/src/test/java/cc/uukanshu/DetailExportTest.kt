package cc.uukanshu

import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.export.EpubExporter
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import cc.uukanshu.ui.detail.DetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class DetailExportTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun twoChapters(): BookRepo.Detail = testDetail(101L, 102L)

    private fun repo(detail: BookRepo.Detail = twoChapters()) = MutableFakeRepo(
        cached = detail,
        fresh = detail,
    ).apply {
        chaptersText[101L] = "body-101"
        chaptersText[102L] = "body-102"
        cachedRefs = detail.chapters
    }

    private fun vm(
        repo: RepoApi,
        scope: CoroutineScope,
        prefs: PrefsApi = MutableFakePrefs(),
    ) = DetailViewModel(
        repo, prefs, T2S(), "1",
        BookDownloadManager({ _, _ -> }, scope),
        EpubExporter(repo, T2S(), tmp.newFolder(), main.dispatcher),
    )

    private fun idle() = main.dispatcher.scheduler.advanceUntilIdle()

    @Test fun exportProducesAReadyFileAndConsumeClearsIt() = runTest(main.dispatcher) {
        val repo = repo()
        val vm = vm(repo, this)
        idle()
        assertEquals(2, vm.ui.value.cached.size)

        vm.exportEpub()
        idle()

        val ready = vm.ui.value.export
        assertTrue("expected Ready, got $ready", ready is DetailViewModel.Export.Ready)
        val file = (ready as DetailViewModel.Export.Ready).file
        assertTrue(file.exists())
        assertEquals("T.epub", file.name)

        vm.consumeExport()
        assertEquals(DetailViewModel.Export.Idle, vm.ui.value.export)
    }

    @Test fun exportUsesTheCurrentScriptPreference() = runTest(main.dispatcher) {
        val repo = repo()
        val vm = vm(repo, this, MutableFakePrefs(simplified = true))
        idle()

        vm.exportEpub()
        idle()

        val export = vm.ui.value.export
        assertTrue("expected Ready, got $export", export is DetailViewModel.Export.Ready)
        val file = (export as DetailViewModel.Export.Ready).file
        val opf = ZipFile(file).use { zip ->
            zip.getInputStream(zip.getEntry("OEBPS/content.opf"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(
            "the VM must pass the current language preference: $opf",
            opf.contains("<dc:language>zh-Hans</dc:language>"),
        )
    }

    @Test fun noCachedTextDoesNotStartAnExport() = runTest(main.dispatcher) {
        val repo = MutableFakeRepo(cached = twoChapters(), fresh = twoChapters())
        val vm = vm(repo, this)
        idle()
        assertTrue(vm.ui.value.cached.isEmpty())

        vm.exportEpub()
        idle()

        assertEquals(DetailViewModel.Export.Idle, vm.ui.value.export)
        assertEquals(0, repo.cachedChapterCalls)
    }

    @Test fun doubleTapStartsOneExport() = runTest(main.dispatcher) {
        val repo = repo()
        val vm = vm(repo, this)
        idle()

        vm.exportEpub()
        vm.exportEpub()
        idle()

        assertEquals(1, repo.cachedChapterCalls)
        assertTrue(vm.ui.value.export is DetailViewModel.Export.Ready)
    }

    @Test fun emptyChapterListFailsWithFriendlyText() = runTest(main.dispatcher) {
        val base = repo()
        val emptyRefs = object : RepoApi by base {
            override suspend fun cachedChapters(bookId: String): List<Parser.ChapterRef> = emptyList()
        }
        val vm = vm(emptyRefs, this)
        idle()

        vm.exportEpub()
        idle()

        assertEquals(
            DetailViewModel.Export.Failed("尚未下載任何章節，請先下載"),
            vm.ui.value.export,
        )
    }

    @Test fun shareFailureSurfacesAndTheButtonWorksAgain() = runTest(main.dispatcher) {
        val repo = repo()
        val vm = vm(repo, this)
        idle()

        vm.exportEpub()
        idle()
        assertTrue(vm.ui.value.export is DetailViewModel.Export.Ready)

        vm.onShareFailed(IllegalStateException("no handler"))
        val failed = vm.ui.value.export as DetailViewModel.Export.Failed
        assertTrue(
            "message should stay user-visible: ${failed.message}",
            failed.message.contains("no handler"),
        )

        vm.exportEpub()
        idle()
        assertTrue(vm.ui.value.export is DetailViewModel.Export.Ready)
    }
}
