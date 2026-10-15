package dev.wind.agent.packages

import dev.wind.agent.shell.ShellPolicy
import dev.wind.agent.shell.ShellRequest
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Uses Gradle's own variant-aware Maven/AAR resolver, not a second dependency engine. */
object GradleProjectDependencies {
    const val TASK = "harnessMateDependenciesV1"
    private val name = Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,127}")

    fun plan(args: JSONObject): ProjectPackagePlan {
        val action = args.optString("action", "resolve")
        require(action in setOf("resolve", "list", "check", "lock")) { "PACKAGE_ACTION_INVALID" }
        require(listOf("packages", "requirements_path", "registry_url", "wheelhouse", "wheel_only",
            "require_hashes", "allow_scripts", "dev").none(args::has)) { "PACKAGE_GRADLE_OPTION_INVALID" }
        val path = args.getString("project_path").trimEnd('/')
        require(path.startsWith("execution://")) { "PACKAGE_EXECUTION_PATH_REQUIRED" }
        val virtual = path.removePrefix("execution://")
        require(virtual.length in 1..1024 && virtual.split('/').all { name.matches(it) }) { "PACKAGE_PATH_INVALID" }
        val module = args.optString("module", ":")
        require(module == ":" || module.startsWith(':') && module.drop(1).split(':').all { name.matches(it) }) {
            "PACKAGE_GRADLE_MODULE_INVALID"
        }
        val array = args.optJSONArray("configurations") ?: JSONArray().put("runtimeClasspath")
        require(array.length() in 1..32) { "PACKAGE_GRADLE_CONFIGURATIONS_INVALID" }
        val configurations = (0 until array.length()).map { array.getString(it) }
        require(configurations.all { name.matches(it) } && configurations.distinct().size == configurations.size) {
            "PACKAGE_GRADLE_CONFIGURATIONS_INVALID"
        }
        val network = args.optBoolean("network", action in setOf("resolve", "lock"))
        val domains = linkedSetOf<String>()
        if (network) domains.addAll(listOf("repo.maven.apache.org", "dl.google.com", "maven.google.com",
            "plugins.gradle.org", "plugins-artifacts.gradle.org", "repo.gradle.org"))
        args.optJSONArray("network_domains")?.let { extra ->
            require(network && extra.length() <= 32) { "PACKAGE_NETWORK_DOMAINS_INVALID" }
            for (i in 0 until extra.length()) {
                val domain = extra.getString(i).lowercase(java.util.Locale.US)
                require(domain.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*"))) { "PACKAGE_NETWORK_DOMAIN_INVALID" }
                domains.add(domain)
            }
        }
        val timeout = args.optLong("timeout_seconds", 300)
        require(timeout in 1..3600) { "PACKAGE_TIMEOUT_INVALID" }
        val mutating = action in setOf("resolve", "lock")
        val script = initScript(module, configurations, action)
        val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val initPath = ".harnessmate-package-cache/gradle/init/$digest.gradle"
        val argv = mutableListOf("--no-daemon", "--console=plain", "--no-configuration-cache",
            "--gradle-user-home", ".harnessmate-package-cache/gradle/user-home",
            "--project-cache-dir", ".harnessmate-package-cache/gradle/project",
            "--dependency-verification=strict", "--init-script", initPath)
        if (!network) argv.add("--offline")
        if (action == "lock") argv.add("--write-locks")
        argv.add(":$TASK")
        val request = ShellRequest("gradle", argv, path, timeoutMs = timeout * 1000,
            network = network, networkDomains = domains, validationCheck = !mutating)
        ShellPolicy.validateRequest(request)
        return ProjectPackagePlan("gradle", action, path, domains, listOf(request), mutating,
            mapOf(initPath to script))
    }

    /** Official Gradle API pattern: resolve artifacts at task execution, preserving variants/transitives. */
    private fun initScript(module: String, configurations: List<String>, action: String): String {
        val options = JSONObject().put("module", module).put("configurations", JSONArray(configurations)).put("action", action)
        return """
            import groovy.json.JsonOutput
            import groovy.json.JsonSlurper
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier
            import java.security.MessageDigest

            gradle.projectsEvaluated {
                def options = new JsonSlurper().parseText('$options')
                def root = gradle.rootProject
                if (root.tasks.findByName('$TASK') != null) throw new GradleException('GRADLE_HARNESS_TASK_CONFLICT')
                root.tasks.register('$TASK') {
                    doLast {
                        if (options.action == 'list') {
                            def projects = root.allprojects.sort { it.path }.collect { p ->
                                [module:p.path, configurations:p.configurations.findAll { it.canBeResolved }.collect { it.name }.sort()]
                            }
                            println('HARNESS_MATE_DEPENDENCIES=' + JsonOutput.toJson([state:'LISTED',projects:projects]))
                            return
                        }
                        def target = root.findProject(options.module)
                        if (target == null) throw new GradleException('GRADLE_PROJECT_NOT_FOUND: ' + options.module)
                        // Validate every selection before resolving anything or persisting locks.
                        def selected = options.configurations.collect { n ->
                            def c = target.configurations.findByName(n)
                            if (c == null || !c.canBeResolved) throw new GradleException('GRADLE_CONFIGURATION_NOT_RESOLVABLE: ' + n)
                            c
                        }
                        selected.each { it.resolutionStrategy.activateDependencyLocking() }
                        def results = selected.collect { c ->
                            def artifacts = c.incoming.artifactView {
                                componentFilter { id -> id instanceof ModuleComponentIdentifier }
                            }.artifacts.artifacts.toList().sort { it.id.displayName }
                            def files = artifacts.collect { a ->
                                def f = a.file
                                def digest = MessageDigest.getInstance('SHA-256')
                                f.withInputStream { stream ->
                                    byte[] buffer = new byte[65536]
                                    int count
                                    while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count)
                                }
                                def id = a.id.componentIdentifier
                                [coordinate:id.group + ':' + id.module + ':' + id.version,
                                 file:f.name, bytes:f.length(), sha256:digest.digest().encodeHex().toString()]
                            }
                            [configuration:c.name, artifacts:files]
                        }
                        println('HARNESS_MATE_DEPENDENCIES=' + JsonOutput.toJson(
                            [state:'RESOLVED',module:target.path,configurations:results,scope:'external-module-artifacts',
                             lock_requested:options.action == 'lock',
                             verification_metadata_present:new File(root.rootDir, 'gradle/verification-metadata.xml').isFile(),
                             trusted_publisher:false]))
                    }
                }
            }
        """.trimIndent() + "\n"
    }
}
