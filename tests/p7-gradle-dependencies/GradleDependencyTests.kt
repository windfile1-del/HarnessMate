package dev.wind.agent.packages

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GradleDependencyTests {
    private var checks = 0
    private fun check(ok: Boolean, message: String) { checks++; if (!ok) error(message) }
    private fun rejects(code: String, args: JSONObject) {
        val error = runCatching { ProjectPackageCommands.plan(args) }.exceptionOrNull()
        check(error?.message?.contains(code) == true, "Expected $code, got $error")
    }
    private fun args(action: String, network: Boolean? = null) = JSONObject()
        .put("manager", "gradle").put("action", action).put("project_path", "execution://main")
        .also { if (network != null) it.put("network", network) }
    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) ->
            z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
        } }
        return out.toByteArray()
    }
    @JvmStatic fun main(argv: Array<String>) {
        val root = File(argv.single()).canonicalFile.apply { mkdirs() }
        val resolve = ProjectPackageCommands.plan(args("resolve"))
        check(resolve.networkDomains.containsAll(setOf("dl.google.com", "repo.maven.apache.org")), "Default Maven hosts")
        check(resolve.mutating && !resolve.commands.single().validationCheck, "Resolve mutation classification")
        check(resolve.commands.single().executable == "gradle", "Installed Gradle, not project wrapper")
        check(resolve.preparedFiles.size == 1 && resolve.preparedFiles.keys.single().startsWith(".harnessmate-package-cache/gradle/init/"), "Support script in ignored cache")
        check(resolve.commands.single().args.contains("--dependency-verification=strict"), "Verification strict preserved")
        check(resolve.preparedFiles.values.single().contains("ModuleComponentIdentifier"), "External artifact view, not just dependency graph")
        for (action in listOf("list", "check")) {
            val p = ProjectPackageCommands.plan(args(action))
            check(p.networkDomains.isEmpty() && p.commands.single().args.contains("--offline"), "$action defaults offline")
            check(!p.mutating && p.commands.single().validationCheck, "$action preserves validation semantics")
        }
        val lock = ProjectPackageCommands.plan(args("lock", false))
        check(lock.mutating && lock.commands.single().args.contains("--write-locks"), "Official lock generation")
        val selected = ProjectPackageCommands.plan(args("resolve").put("module", ":app")
            .put("configurations", JSONArray().put("debugCompileClasspath").put("debugRuntimeClasspath"))
            .put("network_domains", JSONArray().put("repo.example.org")))
        check(selected.networkDomains.contains("repo.example.org"), "Custom repository domain")
        check(selected.preparedFiles.values.single().contains("debugRuntimeClasspath"), "Variant configurations preserved")
        rejects("PACKAGE_GRADLE_MODULE_INVALID", args("resolve").put("module", ":app';println('oops')"))
        rejects("PACKAGE_GRADLE_CONFIGURATIONS_INVALID", args("resolve").put("configurations", JSONArray().put("runtimeClasspath").put("runtimeClasspath")))
        rejects("PACKAGE_GRADLE_CONFIGURATIONS_INVALID", args("resolve").put("configurations", JSONArray().put("x';execute('bad')")))
        rejects("PACKAGE_PATH_INVALID", args("resolve").put("project_path", "execution://main/../other"))
        rejects("PACKAGE_EXECUTION_PATH_REQUIRED", args("resolve").put("project_path", "workspace://main"))
        rejects("PACKAGE_GRADLE_OPTION_INVALID", args("resolve").put("packages", JSONArray().put("x@1.0")))
        rejects("PACKAGE_NETWORK_DOMAINS_INVALID", args("check").put("network_domains", JSONArray().put("repo.example.org")))
        rejects("PACKAGE_TIMEOUT_INVALID", args("resolve").put("timeout_seconds", 3601))
        rejects("PACKAGE_ACTION_INVALID", args("install"))
        rejects("PACKAGE_GRADLE_OPTION_INVALID", args("resolve").put("allow_scripts", true))
        rejects("PACKAGE_GRADLE_OPTION_INVALID", JSONObject().put("manager", "npm").put("action", "list")
            .put("project_path", "execution://main").put("module", ":app"))
        val repo = File(root, "repo").apply { mkdirs() }
        val pomPrefix = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>fixture.android</groupId>"
        fun module(name: String, type: String, bytes: ByteArray, deps: String = "") {
            val dir = File(repo, "fixture/android/$name/1.0").apply { mkdirs() }
            File(dir, "$name-1.0.pom").writeText(pomPrefix + "<artifactId>$name</artifactId><version>1.0</version><packaging>$type</packaging>$deps</project>")
            File(dir, "$name-1.0.$type").writeBytes(bytes)
        }
        val helper = zip(mapOf("fixture.txt" to "transitive jar bytes".toByteArray()))
        val aar = zip(mapOf("classes.jar" to helper, "AndroidManifest.xml" to "<manifest package=\"fixture.android\"/>".toByteArray(),
            "res/values/strings.xml" to "<resources><string name=\"fixture\">fixture</string></resources>".toByteArray()))
        module("helper", "jar", helper)
        module("android-ui", "aar", aar, "<dependencies><dependency><groupId>fixture.android</groupId><artifactId>helper</artifactId><version>1.0</version></dependency></dependencies>")
        val project = File(root, "project").apply { mkdirs() }
        File(project, "settings.gradle").writeText("rootProject.name = 'dependency-fixture'\n")
        File(project, "build.gradle").writeText("plugins { id 'java' }\nrepositories { maven { url = uri('${repo.path.replace('\\', '/')}') } }\ndependencies { implementation 'fixture.android:android-ui:1.0' }\n")
        val exported = JSONObject()
        for (action in listOf("list", "resolve", "lock", "check", "bad-config")) {
            val a = args(if (action == "bad-config") "check" else action, false)
            if (action == "bad-config") a.put("configurations", JSONArray().put("doesNotExist"))
            val p = ProjectPackageCommands.plan(a)
            p.preparedFiles.forEach { (name, body) -> File(project, name).apply { parentFile.mkdirs(); writeText(body) } }
            exported.put(action, JSONArray(p.commands.single().args))
        }
        exported.put("project", project.path).put("repo", repo.path)
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        exported.put("expected", JSONObject().put("android-ui-1.0.aar", hash(aar)).put("helper-1.0.jar", hash(helper)))
        File(root, "plans.json").writeText(exported.toString())
        println("GradleDependencyTests PASS ($checks checks); actual production init scripts exported")
    }
}
