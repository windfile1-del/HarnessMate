package dev.wind.agent.coding

/**
 * Turns a detected project into the commands worth running.
 *
 * The rules are the boring, widely-true ones for each ecosystem, and the plan is
 * deterministic: same profile, same plan. The interesting decisions are the
 * negative ones — not inventing a test command when no test runner exists, and
 * not marking a check "fast" when it is really the whole build.
 */
object ValidationPlanner {

    fun plan(profile: ProjectProfile): ValidationPlan {
        val plan = when (profile.kind) {
        ProjectKind.ANDROID_GRADLE -> gradlePlan(profile, android = true)
        ProjectKind.JVM_GRADLE -> gradlePlan(profile, android = false)
        ProjectKind.MAVEN -> mavenPlan(profile)
        ProjectKind.NODE -> nodePlan(profile)
        ProjectKind.PYTHON -> pythonPlan(profile)
        ProjectKind.RUST -> rustPlan(profile)
        ProjectKind.GO -> goPlan(profile)
        ProjectKind.GENERIC -> ValidationPlan(
            confidence = Confidence.LOW,
            reason = "No recognised build system found; run commands yourself to decide what to check."
        )
        }
        return plan.copy(
            fastChecks = plan.fastChecks.map { it.copy(cwd = profile.root) },
            finalChecks = plan.finalChecks.map { it.copy(cwd = profile.root) }
        )
    }

    private fun gradlePlan(profile: ProjectProfile, android: Boolean): ValidationPlan {
        val runner = profile.wrapper ?: "gradle"
        val fast = mutableListOf<ValidationCommand>()
        val final = mutableListOf<ValidationCommand>()

        // Gradle owns Java/Kotlin joint compilation. JVM classes works for
        // either language; Android packaging remains a final check.
        val fastTask = if (android) {
            if (profile.kotlinPlugin) "compileDebugKotlin" else "compileDebugJavaWithJavac"
        } else "classes"
        fast.add(
            ValidationCommand(
                id = "compile",
                label = "Compile ($fastTask)",
                argv = listOf(runner, fastTask, "--offline", "--console=plain"),
                timeoutMs = 900_000L
            )
        )

        final.add(
            ValidationCommand(
                id = "build",
                label = if (android) "Assemble debug APK" else "Build",
                argv = listOf(runner, if (android) "assembleDebug" else "build", "--offline", "--console=plain"),
                timeoutMs = 1_800_000L
            )
        )
        val hasTests = profile.testFrameworkHints.isNotEmpty() ||
            profile.modules.any { it.isNotEmpty() }
        if (hasTests) {
            final.add(
                ValidationCommand(
                    id = "test",
                    label = "Unit tests",
                    argv = listOf(runner, if (android) "testDebugUnitTest" else "test", "--offline", "--console=plain"),
                    timeoutMs = 1_800_000L
                )
            )
        }
        return ValidationPlan(
            fastChecks = fast,
            finalChecks = final,
            confidence = profile.confidence,
            reason = if (hasTests) {
                "Gradle project with a discoverable test setup."
            } else {
                "Gradle project; no test task was discovered, so only compilation and packaging are checked."
            }
        )
    }

    private fun mavenPlan(profile: ProjectProfile): ValidationPlan = ValidationPlan(
        fastChecks = listOf(
            ValidationCommand("compile", "Compile", listOf("mvn", "-q", "-o", "compile"), 900_000L)
        ),
        finalChecks = listOf(
            ValidationCommand("test", "Tests", listOf("mvn", "-q", "-o", "test"), 1_800_000L)
        ),
        confidence = profile.confidence,
        reason = "Maven project."
    )

    private fun nodePlan(profile: ProjectProfile): ValidationPlan {
        // Only a declared test script earns a command. A framework appearing in
        // devDependencies says how tests are written, not that there is a script
        // to run; inventing one fails the build for reasons that have nothing to
        // do with the code under test.
        val hasTests = profile.testFrameworkHints.contains("npm-test")
        val fast = mutableListOf<ValidationCommand>()
        if (profile.testFrameworkHints.contains("typescript")) {
            fast.add(ValidationCommand("typecheck", "TypeScript typecheck", listOf("npx", "tsc", "--noEmit"), 600_000L))
        }
        val final = mutableListOf<ValidationCommand>()
        if (hasTests) {
            final.add(ValidationCommand("test", "npm test", listOf("npm", "test", "--silent"), 1_800_000L))
        }
        return ValidationPlan(
            fastChecks = fast,
            finalChecks = final,
            confidence = profile.confidence,
            reason = if (hasTests) "Node project with a test script." else "Node project; no test script or runner was found."
        )
    }

    private fun pythonPlan(profile: ProjectProfile): ValidationPlan {
        val hasPytest = profile.testFrameworkHints.contains("pytest")
        val fast = listOf(
            ValidationCommand("compile", "Byte-compile", listOf("python3", "-m", "compileall", "-q", "."), 300_000L)
        )
        val final = mutableListOf<ValidationCommand>()
        if (hasPytest) {
            final.add(ValidationCommand("test", "pytest", listOf("python3", "-m", "pytest", "-q"), 1_800_000L))
        }
        // A Python project without pytest still has a real final syntax check.
        final.add(ValidationCommand("syntax", "Final byte-compile", listOf("python3", "-m", "compileall", "-q", "."), 300_000L))
        return ValidationPlan(
            fastChecks = fast,
            finalChecks = final,
            confidence = profile.confidence,
            reason = if (hasPytest) "Python project using pytest." else "Python project; no pytest configuration was found."
        )
    }

    private fun rustPlan(profile: ProjectProfile): ValidationPlan = ValidationPlan(
        fastChecks = listOf(ValidationCommand("check", "cargo check", listOf("cargo", "check", "--quiet"), 1_200_000L)),
        finalChecks = listOf(ValidationCommand("test", "cargo test", listOf("cargo", "test", "--quiet"), 1_800_000L)),
        confidence = profile.confidence,
        reason = "Rust crate."
    )

    private fun goPlan(profile: ProjectProfile): ValidationPlan = ValidationPlan(
        fastChecks = listOf(ValidationCommand("build", "go build", listOf("go", "build", "./..."), 900_000L)),
        finalChecks = listOf(ValidationCommand("test", "go test", listOf("go", "test", "./..."), 1_800_000L)),
        confidence = profile.confidence,
        reason = "Go module."
    )
}
