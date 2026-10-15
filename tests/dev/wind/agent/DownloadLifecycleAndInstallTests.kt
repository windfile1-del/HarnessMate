package dev.wind.agent

import dev.wind.agent.artifact.*
import dev.wind.agent.capability.*
import dev.wind.agent.history.MutationJournal
import dev.wind.agent.history.MutationScope
import dev.wind.agent.project.MountMode
import dev.wind.agent.project.VegaProject
import dev.wind.agent.project.WorkspaceMount
import dev.wind.agent.workspace.WorkspaceEntry
import dev.wind.agent.workspace.WorkspaceFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DownloadLifecycleAndInstallTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-lifecycle-test-").toFile()
        try {
            testListDownloadsAndFormatDetection(root.toPath())
            testDeleteDownloadAndReferenceCounting(root.toPath())
            testCleanupDownloadsTaskAndAll(root.toPath())
            testPrepareAndApplyFileDownload(root.toPath())
            testPrepareAndApplyZipDownload(root.toPath())
            testApkInstallLifecycle(root.toPath())
            testVlcApkInspectionIfPresent()
            testSha256ChecksumFileTextFetch()
            println("DownloadLifecycleAndInstallTests: OK")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testVlcApkInspectionIfPresent() {
        val vlcFile = File(".vlc-arm64.apk")
        if (vlcFile.isFile) {
            Files.newByteChannel(vlcFile.toPath(), setOf(java.nio.file.StandardOpenOption.READ)).use { ch ->
                val summary = ZipDirectoryInspector().inspect(ch)
                check(summary.entryCount == 2618) { "VLC entryCount mismatch: ${summary.entryCount}" }
                check(summary.apkCandidate) { "VLC must be apkCandidate" }
                check(!summary.jarCandidate) { "VLC must not be jarCandidate" }
            }
        }
    }

    private fun testSha256ChecksumFileTextFetch() {
        val text = "bea9ea0cdecfd8f461ef305d2afe5d1ac1459cfb9156acce2f615788a486de74  vlc.apk\n"
        val data = text.toByteArray()
        val transport = ArtifactHttpTransport { _, _ ->
            ArtifactHttpResponse(200, data.size.toLong(), null,
                ByteArrayInputStream(data),
                contentType = "application/octet-stream") { }
        }
        val fetcher = AuthorizedTextFetcher(transport) { true }
        val fetched = fetcher.fetch("https://example.com/vlc.apk.sha256", "checksum", 1024, CancellationToken())
        check(fetched.utf8Text.startsWith("bea9ea0cdecfd8f461ef305d2afe5d1ac1459cfb9156acce2f615788a486de74"))
    }

    private fun testListDownloadsAndFormatDetection(root: Path) {
        val store = VerifiedArtifactStore(root.resolve("list-store").toFile())
        val access = TaskArtifactAccess(root.resolve("list-access").toFile())

        // 1. Text/binary file
        val textBytes = "Hello Vega Safe".toByteArray()
        val textSha = sha256(textBytes)
        val textArt = store.importQuarantined(ByteArrayInputStream(textBytes), textSha, 1024, ArtifactProvenance("https://example.com/file.txt"))
        access.record("task-1", "proj-1", 1L, textArt)

        // 2. Normal ZIP file
        val zipBytes = createZipBytes(mapOf("content.txt" to "sample text"))
        val zipSha = sha256(zipBytes)
        val zipArt = store.importQuarantined(ByteArrayInputStream(zipBytes), zipSha, 10240, ArtifactProvenance("https://example.com/archive.zip"))
        access.record("task-1", "proj-1", 1L, zipArt)

        // 3. Candidate APK file (contains AndroidManifest.xml and resources.arsc and classes.dex)
        val apkBytes = createZipBytes(mapOf(
            "AndroidManifest.xml" to "binary-xml-stub",
            "resources.arsc" to "resources-stub",
            "classes.dex" to "dex-stub"
        ))
        val apkSha = sha256(apkBytes)
        val apkArt = store.importQuarantined(ByteArrayInputStream(apkBytes), apkSha, 20480, ArtifactProvenance("https://example.com/app.apk"))
        access.record("task-1", "proj-1", 1L, apkArt)

        // Query task-1
        val task1Artifacts = access.listArtifacts("task-1")
        check(task1Artifacts.size == 3) { "Expected 3 artifacts for task-1, got ${task1Artifacts.size}" }
        check(access.hasAccess("task-1", textArt.uri))
        check(access.hasAccess("task-1", zipArt.uri))
        check(access.hasAccess("task-1", apkArt.uri))

        // Task isolation: task-2 cannot see task-1's artifacts
        val task2Artifacts = access.listArtifacts("task-2")
        check(task2Artifacts.isEmpty()) { "Task-2 should have no artifacts" }
        check(!access.hasAccess("task-2", textArt.uri))
    }

    private fun testDeleteDownloadAndReferenceCounting(root: Path) {
        val store = VerifiedArtifactStore(root.resolve("del-store").toFile())
        val access = TaskArtifactAccess(root.resolve("del-access").toFile())

        val bytes = "Shared payload across tasks".toByteArray()
        val sha = sha256(bytes)
        val art = store.importQuarantined(ByteArrayInputStream(bytes), sha, 1024, ArtifactProvenance("https://example.com/shared.dat"))

        // Task-A and Task-B both hold a grant to this artifact
        access.record("task-A", "proj-1", 1L, art)
        access.record("task-B", "proj-1", 1L, art)
        check(access.countActiveGrants(art.uri) == 2)

        // Task-A deletes reference
        val revokedA = access.revokeAccess("task-A", art.uri)
        check(revokedA) { "revokeAccess for task-A failed" }
        check(!access.hasAccess("task-A", art.uri)) { "task-A should no longer have access" }
        check(access.hasAccess("task-B", art.uri)) { "task-B should still have access" }
        check(access.countActiveGrants(art.uri) == 1) { "Expected 1 active grant remaining" }

        // Since task-B still references it, store must retain the blob
        check(store.getArtifact(art.uri) != null) { "Store should retain shared artifact" }

        // Task-B deletes reference
        val revokedB = access.revokeAccess("task-B", art.uri)
        check(revokedB) { "revokeAccess for task-B failed" }
        check(access.countActiveGrants(art.uri) == 0) { "Expected 0 active grants" }

        // Now physically delete from store
        val deletedFromStore = store.deleteArtifact(art.uri)
        check(deletedFromStore) { "deleteArtifact should return true" }
        check(store.getArtifact(art.uri) == null) { "Artifact should be deleted from store" }
    }

    private fun testCleanupDownloadsTaskAndAll(root: Path) {
        val store = VerifiedArtifactStore(root.resolve("clean-store").toFile())
        val access = TaskArtifactAccess(root.resolve("clean-access").toFile())

        val art1 = store.importQuarantined(ByteArrayInputStream("file1".toByteArray()), sha256("file1".toByteArray()), 1024, ArtifactProvenance("https://example.com/1"))
        val art2 = store.importQuarantined(ByteArrayInputStream("file2".toByteArray()), sha256("file2".toByteArray()), 1024, ArtifactProvenance("https://example.com/2"))
        val art3 = store.importQuarantined(ByteArrayInputStream("file3".toByteArray()), sha256("file3".toByteArray()), 1024, ArtifactProvenance("https://example.com/3"))

        access.record("task-X", "proj-1", 1L, art1)
        access.record("task-X", "proj-1", 1L, art2)
        access.record("task-Y", "proj-1", 1L, art3)

        // Task-X cleans up its own downloads
        val taskXArtifacts = access.listArtifacts("task-X")
        check(taskXArtifacts.size == 2)
        for (uri in taskXArtifacts) {
            access.revokeAccess("task-X", uri)
            if (access.countActiveGrants(uri) == 0) {
                store.deleteArtifact(uri)
            }
        }
        check(access.listArtifacts("task-X").isEmpty())
        check(store.getArtifact(art1.uri) == null)
        check(store.getArtifact(art2.uri) == null)
        check(store.getArtifact(art3.uri) != null) { "art3 owned by task-Y must be retained" }
    }

    private fun testPrepareAndApplyFileDownload(root: Path) {
        val privateRoot = root.resolve("apply-file-private").toFile().apply { mkdirs() }
        val store = VerifiedArtifactStore(File(privateRoot, "p7-artifacts"))
        val access = TaskArtifactAccess(File(privateRoot, "p7-artifact-access"))
        val planStore = DownloadApplyPlanStore(privateRoot)

        val fileData = "Binary output payload for workspace".toByteArray()
        val fileSha = sha256(fileData)
        val artifact = store.importQuarantined(ByteArrayInputStream(fileData), fileSha, 4096, ArtifactProvenance("https://example.com/tool.bin"))
        access.record("task-apply", "proj-1", 1L, artifact)

        val plan = DownloadApplyPlan(
            taskId = "task-apply",
            projectId = "proj-1",
            generation = 1L,
            mountId = "m-main",
            relativePath = "bin/tool.bin",
            artifactUri = artifact.uri,
            artifactSha256 = artifact.sha256,
            mode = "file",
            overwrite = false,
            stripSingleRoot = false,
            entryCount = 1,
            totalBytes = fileData.size.toLong()
        )
        val planId = planStore.issue(plan)
        val loaded = planStore.load(TaskContext("task-apply", "proj-1", 1L), planId)
        check(loaded == plan)

        val memFs = InMemoryWorkspaceFs()
        val journal = MutationJournal.get(FakeAndroidContext(privateRoot))
        val applier = DownloadWorkspaceApplier(store, journal)
        val scope = MutationScope("proj-1", "m-main", "content://main")

        val result = applier.apply(loaded, memFs, scope, CancellationToken()) { }
        check(result.getString("status") == "APPLIED")
        check(memFs.exists("bin/tool.bin"))
        check(memFs.read("bin/tool.bin").contentEquals(fileData))
    }

    private fun testPrepareAndApplyZipDownload(root: Path) {
        val privateRoot = root.resolve("apply-zip-private").toFile().apply { mkdirs() }
        val store = VerifiedArtifactStore(File(privateRoot, "p7-artifacts"))
        val access = TaskArtifactAccess(File(privateRoot, "p7-artifact-access"))
        val planStore = DownloadApplyPlanStore(privateRoot)

        // Create a GitHub-like ZIP with a single root directory prefix: "my-repo-HEAD/"
        val zipEntries = mapOf(
            "my-repo-HEAD/README.md" to "# My Repo\nSample README",
            "my-repo-HEAD/src/Main.kt" to "fun main() = println(\"Hi\")",
            "my-repo-HEAD/docs/architecture.txt" to "Architecture notes"
        )
        val zipData = createZipBytes(zipEntries)
        val zipSha = sha256(zipData)
        val artifact = store.importQuarantined(ByteArrayInputStream(zipData), zipSha, 65536, ArtifactProvenance("https://codeload.github.com/org/my-repo/zip/HEAD"))
        access.record("task-zip", "proj-1", 1L, artifact)

        val plan = DownloadApplyPlan(
            taskId = "task-zip",
            projectId = "proj-1",
            generation = 1L,
            mountId = "m-main",
            relativePath = "imported-repo",
            artifactUri = artifact.uri,
            artifactSha256 = artifact.sha256,
            mode = "zip",
            overwrite = false,
            stripSingleRoot = true,
            entryCount = 3,
            totalBytes = zipData.size.toLong()
        )
        val planId = planStore.issue(plan)
        val loaded = planStore.load(TaskContext("task-zip", "proj-1", 1L), planId)

        val memFs = InMemoryWorkspaceFs()
        val journal = MutationJournal.get(FakeAndroidContext(privateRoot))
        val applier = DownloadWorkspaceApplier(store, journal)
        val scope = MutationScope("proj-1", "m-main", "content://main")

        val result = applier.apply(loaded, memFs, scope, CancellationToken()) { }
        check(result.getString("status") == "APPLIED")
        check(result.getInt("files_written") == 3)

        // Verified single root was stripped: files exist directly under "imported-repo/"
        check(memFs.exists("imported-repo/README.md")) { "README.md missing" }
        check(memFs.readText("imported-repo/README.md") == "# My Repo\nSample README")
        check(memFs.exists("imported-repo/src/Main.kt")) { "Main.kt missing" }
        check(memFs.exists("imported-repo/docs/architecture.txt")) { "architecture.txt missing" }
    }

    private fun testApkInstallLifecycle(root: Path) {
        val privateRoot = root.resolve("apk-install-private").toFile().apply { mkdirs() }
        val store = VerifiedArtifactStore(File(privateRoot, "p7-artifacts"))
        val access = TaskArtifactAccess(File(privateRoot, "p7-artifact-access"))
        val planStore = ApkInstallPlanStore(privateRoot)
        val tracker = ApkInstallTracker.forDirectory(File(privateRoot, "apk-tracker"))

        val apkBytes = createZipBytes(mapOf(
            "AndroidManifest.xml" to "binary-xml-stub",
            "resources.arsc" to "resources-stub",
            "classes.dex" to "dex-stub"
        ))
        val apkSha = sha256(apkBytes)
        val artifact = store.importQuarantined(ByteArrayInputStream(apkBytes), apkSha, 20480, ArtifactProvenance("https://example.com/sample.apk"))
        access.record("task-apk", "proj-1", 1L, artifact)

        val plan = ApkInstallPlan(
            taskId = "task-apk",
            projectId = "proj-1",
            generation = 1L,
            artifactUri = artifact.uri,
            artifactSha256 = artifact.sha256,
            packageName = "com.example.sampleapp",
            versionCode = 101L,
            versionName = "1.0.1",
            label = "Sample App",
            byteSize = apkBytes.size.toLong()
        )
        val planId = planStore.issue(plan)
        val loaded = planStore.load(TaskContext("task-apk", "proj-1", 1L), planId)
        check(loaded == plan)

        // Test mock installer execution
        val operationId = "op-test-123"
        tracker.recordOperation(operationId, plan.packageName, plan.artifactUri, "SUBMITTING", "Submitting session")
        var record = tracker.getStatus(operationId)
        check(record?.state == "SUBMITTING")

        // Simulate user confirmation and success
        tracker.updateStatus(operationId, "PENDING_USER_CONFIRMATION", "Waiting for confirmation")
        record = tracker.getStatus(operationId)
        check(record?.state == "PENDING_USER_CONFIRMATION")

        tracker.updateStatus(operationId, "SUCCESS", "Application installed successfully")
        record = tracker.getStatus(operationId)
        check(record?.state == "SUCCESS")
        check(record?.packageName == "com.example.sampleapp")
    }

    private fun createZipBytes(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private class FakeAndroidContext(val root: File) : android.content.Context() {
        override fun getFilesDir(): File = root.resolve("files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = root.resolve("nobackup").apply { mkdirs() }
        override fun getApplicationContext(): android.content.Context = this
    }

    private class InMemoryWorkspaceFs : WorkspaceFs {
        val files = mutableMapOf<String, ByteArray>()
        val dirs = mutableSetOf<String>()

        override fun isConfigured(): Boolean = true
        override fun getRootDisplayName(): String = "InMemoryWorkspace"
        override fun list(path: String): List<WorkspaceEntry> {
            val prefix = if (path.isEmpty()) "" else "$path/"
            return (files.keys.filter { it.startsWith(prefix) } + dirs.filter { it.startsWith(prefix) })
                .map { name ->
                    val rel = name.removePrefix(prefix).substringBefore('/')
                    val isDir = dirs.contains(if (prefix.isEmpty()) rel else "$prefix$rel")
                    WorkspaceEntry(rel, if (prefix.isEmpty()) rel else "$prefix$rel", isDir, 0L, 0L)
                }.distinctBy { it.path }
        }
        override fun read(path: String): ByteArray = files[path] ?: throw java.io.FileNotFoundException(path)
        override fun readText(path: String, charset: String): String = String(read(path), java.nio.charset.Charset.forName(charset))
        override fun write(path: String, data: ByteArray) { files[path] = data }
        override fun writeText(path: String, text: String, charset: String) { write(path, text.toByteArray(java.nio.charset.Charset.forName(charset))) }
        override fun mkdir(path: String): Boolean { dirs.add(path); return true }
        override fun delete(path: String): Boolean { files.remove(path); dirs.remove(path); return true }
        override fun move(from: String, to: String): Boolean {
            val data = files.remove(from) ?: return false
            files[to] = data
            return true
        }
        override fun exists(path: String): Boolean = files.containsKey(path) || dirs.contains(path)
        override fun stat(path: String): WorkspaceEntry? {
            if (files.containsKey(path)) return WorkspaceEntry(path.substringAfterLast('/'), path, false, files[path]!!.size.toLong(), 0L)
            if (dirs.contains(path)) return WorkspaceEntry(path.substringAfterLast('/'), path, true, 0L, 0L)
            return null
        }
        override fun search(query: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> = emptyList()
        override fun glob(pattern: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> = emptyList()
        override fun openInputStream(path: String): InputStream = ByteArrayInputStream(read(path))
        override fun openOutputStream(path: String): OutputStream {
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    write(path, toByteArray())
                }
            }
        }
    }
}
