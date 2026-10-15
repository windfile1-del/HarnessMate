package dev.wind.agent

import dev.wind.agent.artifact.*
import dev.wind.agent.build.*
import dev.wind.agent.capability.*
import dev.wind.agent.coding.*
import dev.wind.agent.project.*
import dev.wind.agent.workspace.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MavenDependencyWorkspaceTests {
    @JvmStatic fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "maven-workspace-")
        try {
            issuedPlanIsolationAndTamper(root)
            capabilityUsesStoredPlanNotModelFields(root)
            workspacePublicationAndPrivateCache(root)
            conflictCancellationRevocationAndBadJar(root)
            codingMirrorGuard()
            productionDispatchContract()
            println("MavenDependencyWorkspaceTests: OK")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                it.toFile().setWritable(true)
                Files.deleteIfExists(it)
            } }
        }
    }

    private class MemoryFs : WorkspaceFs {
        val files = linkedMapOf<String, ByteArray>()
        val directories = mutableSetOf("", "project")
        override fun isConfigured() = true
        override fun getRootDisplayName() = "fixture SAF"
        override fun list(path: String): List<WorkspaceEntry> = emptyList()
        override fun read(path: String) = files[path]?.copyOf() ?: error("missing fixture: $path")
        override fun readText(path: String, charset: String) = String(read(path), charset(charset))
        override fun write(path: String, data: ByteArray) { files[path] = data.copyOf() }
        override fun writeText(path: String, text: String, charset: String) = write(path, text.toByteArray(charset(charset)))
        override fun mkdir(path: String) = directories.add(path)
        override fun delete(path: String) = files.remove(path) != null
        override fun move(from: String, to: String): Boolean = error("not used")
        override fun exists(path: String) = path in files || path in directories
        override fun stat(path: String): WorkspaceEntry? = when {
            path in directories -> WorkspaceEntry(path, path.substringAfterLast('/'), true, 0L, 0L)
            path in files -> WorkspaceEntry(path, path.substringAfterLast('/'), false, read(path).size.toLong(), 0L)
            else -> null
        }
        override fun search(query: String, maxResults: Int, scopePath: String) = emptyList<WorkspaceEntry>()
        override fun glob(pattern: String, maxResults: Int, scopePath: String) = emptyList<WorkspaceEntry>()
        override fun openInputStream(path: String): InputStream = read(path).inputStream()
        override fun openOutputStream(path: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { this@MemoryFs.write(path, toByteArray()); super.close() }
        }
    }

    private class Fixture(root: Path, name: String, jarBytes: ByteArray = jar()) {
        val privateRoot = Files.createDirectory(root.resolve(name)).toFile().canonicalFile
        val cas = VerifiedArtifactStore(File(privateRoot, "cas"))
        val coordinate = MavenCoordinate.parse("g:tiny:1")
        val xml = "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>" +
            "<artifactId>tiny</artifactId><version>1</version></project>"
        val pom = imported(xml.toByteArray())
        val jar = imported(jarBytes)
        val download = MavenDownloadedModule(coordinate, "fixture",
            MavenPomPin(coordinate, "fixture", pom.uri, pom.sha256, pom.byteSize.toInt()), jar,
            "https://repo.example/g/tiny/1/tiny-1.pom", "https://repo.example/g/tiny/1/tiny-1.jar",
            MavenChecksumKind.EXPECTED_SHA256, MavenChecksumKind.EXPECTED_SHA256)
        val graph = MavenCompileGraphPlanner(CasMavenPomSource(cas, listOf(download.pomPin))).plan(listOf(coordinate))
        val lock = MavenDependencyLock.fromGraph(listOf(coordinate), graph, listOf(download))
        val context = TaskContext("task", "p", 3L)
        val fs = MemoryFs()
        val plan = MavenDependencyInstallPlan(context.taskId, context.projectId, context.projectGeneration,
            "main", "project", null, lock)
        val plans = MavenDependencyPlanStore(privateRoot)
        fun imported(bytes: ByteArray) = cas.importQuarantined(bytes.inputStream(),
            MavenDependencyInstallPlan.digest(bytes), bytes.size.toLong(), ArtifactProvenance("https://repo.example/"))
        fun installer() = MavenDependencyWorkspaceInstaller(privateRoot, cas)
    }

    private fun issuedPlanIsolationAndTamper(root: Path) {
        val fixture = Fixture(root, "plans")
        val plan = fixture.plan
        check(MavenDependencyInstallPlan.decode(plan.encode()) == plan)
        check(plan.copy(relativeRoot = "another").id != plan.id)
        check(plan.copy(relativeRoot = "another").targetId != plan.targetId)
        check(plan.copy(previousLockSha256 = "a".repeat(64)).id != plan.id)
        val id = fixture.plans.issue(plan)
        check(fixture.plans.load(fixture.context, id) == plan)
        for (context in listOf(fixture.context.copy(taskId = "other"), fixture.context.copy(projectId = "other"),
            fixture.context.copy(projectGeneration = 4L))) {
            expectFailure("ARTIFACT_TASK_ACCESS_DENIED") { fixture.plans.load(context, id) }
        }
        val forgedId = "f".repeat(64)
        TaskArtifactAccess(File(fixture.privateRoot, "ordinary-downloads")).record(
            plan.taskId, plan.projectId, plan.generation,
            VerifiedArtifact("artifact://sha256/$forgedId", forgedId, 1L))
        expectFailure("ARTIFACT_TASK_ACCESS_DENIED") { fixture.plans.load(fixture.context, forgedId) }
        val file = File(fixture.privateRoot, "p7-maven-plans/$id.plan")
        file.setWritable(true)
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        expectFailure("MAVEN_INSTALL_PLAN_DIGEST_MISMATCH") { fixture.plans.load(fixture.context, id) }
    }

    private fun capabilityUsesStoredPlanNotModelFields(root: Path) {
        val fixture = Fixture(root, "capability")
        val id = fixture.plans.issue(fixture.plan)
        val mount = WorkspaceMount("main", "main", "content://fixture", "Fixture", MountMode.READ_WRITE, "main", true)
        val project = VegaProject("p", "Fixture", "main", listOf(mount), generation = 3L)
        val resolver = CapabilityResolver({ if (it == project.id) project else null }, { task, plan -> fixture.plans.load(task, plan) })
        val args = JSONObject().put("plan_id", id).put("target_mount_id", "attacker")
            .put("target_project_path", "file:///private").put("plan_sha256", "f".repeat(64))
        val requirement = resolver.resolve(fixture.context, "install_maven_dependencies", args) as CapabilityRequirement.Composite
        val install = requirement.requirements.filterIsInstance<CapabilityRequirement.ArtifactInstall>().single()
        check(install.artifactSha256 == id && install.targetMountId == "main" && install.targetId == fixture.plan.targetId)
        val broker = CapabilityBrokerImpl(CapabilityStore.inMemory(), { if (it == project.id) project else null },
            { MountState.AVAILABLE })
        val generic = broker.check(fixture.context, CapabilityRequirement.CanonicalWrite(project.id, setOf("main")))
            as CapabilityCheck.NeedsApproval
        broker.grant(generic.request.requestId, CapabilityDecision.Allow(GrantScope.TASK)) ?: error("write grant failed")
        val requested = broker.check(fixture.context, requirement) as CapabilityCheck.NeedsApproval
        check(GrantScope.APP_SESSION !in requested.request.allowedScopes)
        check(broker.grant(requested.request.requestId, CapabilityDecision.Allow(GrantScope.APP_SESSION)) == null)
        val pending = broker.check(fixture.context, requirement) as CapabilityCheck.NeedsApproval
        val lease = broker.grant(pending.request.requestId, CapabilityDecision.Allow(GrantScope.ONCE)) ?: error("install grant failed")
        broker.verify(lease, requirement)
        broker.consume(lease)
        check(broker.check(fixture.context, requirement) is CapabilityCheck.NeedsApproval)
        check(resolver.resolve(fixture.context.copy(projectGeneration = 4L), "install_maven_dependencies", args)
            is CapabilityRequirement.Denied)
    }

    private fun workspacePublicationAndPrivateCache(root: Path) {
        val fixture = Fixture(root, "install")
        var publications = 0
        fixture.installer().install(fixture.plan, fixture.fs, CancellationToken(), {}) { plan, bytes ->
            check(plan == fixture.plan)
            publications++
            fixture.fs.mkdir(plan.metadataPath)
            fixture.fs.write(plan.lockPath, bytes)
        }
        check(publications == 1 && fixture.fs.files.keys == setOf(fixture.plan.lockPath))
        check(MavenDependencyLock.decode(fixture.fs.read(fixture.plan.lockPath)) == fixture.lock)
        val cache = File(fixture.privateRoot, "p7-maven-cache")
        val cached = File(cache, "${fixture.jar.sha256}.jar")
        check(MavenDependencyInstallPlan.digest(cached.readBytes()) == fixture.jar.sha256)
        // A local build snapshot receives only the SAF lock; JARs remain private.
        val mirror = Files.createDirectory(fixture.privateRoot.toPath().resolve("snapshot"))
        Files.createDirectory(mirror.resolve(".vega"))
        Files.write(mirror.resolve(".vega/maven-dependencies.lock"), fixture.fs.read(fixture.plan.lockPath))
        val offline = MavenLockedArtifactStore(mirror.toFile(), cache).resolveGraph(listOf(fixture.coordinate.notation))
            ?: error("offline graph unavailable")
        check(offline.single().file.canonicalFile == cached.canonicalFile)
        expectFailure("MAVEN_INSTALL_LOCK_CONFLICT") {
            fixture.installer().install(fixture.plan, fixture.fs, CancellationToken(), {}) { _, _ -> error("must not publish twice") }
        }
    }

    private fun conflictCancellationRevocationAndBadJar(root: Path) {
        val conflict = Fixture(root, "conflict")
        conflict.fs.write(conflict.plan.lockPath, "changed".toByteArray())
        expectFailure("MAVEN_INSTALL_LOCK_CONFLICT") {
            conflict.installer().install(conflict.plan, conflict.fs, CancellationToken(), {}) { _, _ -> error("published conflict") }
        }
        check(!File(conflict.privateRoot, "p7-maven-cache").exists())
        val revoked = Fixture(root, "revoked")
        expectFailure("REVOKED") {
            revoked.installer().install(revoked.plan, revoked.fs, CancellationToken(), { error("REVOKED") }) { _, _ -> error("published revoked") }
        }
        check(revoked.fs.files.isEmpty())
        val cancelled = Fixture(root, "cancelled")
        val token = CancellationToken().also { it.cancel() }
        expectFailure("cancelled") {
            cancelled.installer().install(cancelled.plan, cancelled.fs, token, {}) { _, _ -> error("published cancelled") }
        }
        val malformed = Fixture(root, "bad-jar", byteArrayOf(1, 2, 3))
        expectFailure("ZIP_") {
            malformed.installer().install(malformed.plan, malformed.fs, CancellationToken(), {}) { _, _ -> error("published bad JAR") }
        }
        check(malformed.fs.files.isEmpty())
    }

    private fun codingMirrorGuard() {
        val runtime = CodingTaskRuntime(object : CodingRuntimeHost {
            override fun mirrorDigest() = "source"
            override fun runCommand(command: ValidationCommand, token: CancellationToken) = error("not used")
            override fun now() = 0L
        })
        runtime.start("task", "p", 0L, restore = false)
        check(runtime.beforeTool("install_maven_dependencies") == null)
        runtime.beforeTool("execution_prepare")
        check(runtime.beforeTool("install_maven_dependencies")?.startsWith("P4_DIRECT_CANONICAL_MUTATION_DENIED") == true)
    }

    private fun productionDispatchContract() {
        check(ToolRegistry.spec("resolve_maven_dependencies")?.effect == ToolEffect.NETWORK)
        check(ToolRegistry.spec("install_maven_dependencies")?.effect == ToolEffect.CANONICAL_WRITE)
        check(ToolRegistry.isMutating("install_maven_dependencies"))
        val tools = File("src/dev/wind/agent/Tools.kt").readText()
        check("workspaceTools.resolveMavenDependencies" in tools && "workspaceTools.installMavenDependencies" in tools)
        val workspace = File("src/dev/wind/agent/workspace/WorkspaceTools.kt").readText()
        check("journal.transactionalWrite(scope, target.fs" in workspace)
        check("assertCanonicalDependencyInstallSafe()" in workspace)
        check("verifyModelGraph = true" in File("src/dev/wind/agent/build/MavenDependencyWorkspaceInstaller.kt").readText())
    }

    private fun jar(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\r\n\r\n".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
    private fun expectFailure(message: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        check(error != null && error.message?.contains(message, true) == true) { "expected $message, got $error" }
    }
}
