package dev.wind.agent

import dev.wind.agent.project.*

object ProjectPathTests {
    @JvmStatic fun main(args: Array<String>) {
        val m1 = WorkspaceMount("m1", "main", "content://tree/main", "Main", MountMode.READ_WRITE, "ISERA", isPrimary = true)
        val m2 = WorkspaceMount("m2", "shared", "content://tree/shared", "Shared", MountMode.READ_WRITE, "SharedLib")
        val m3 = WorkspaceMount("m3", "sdk", "content://tree/sdk", "SDK", MountMode.READ_ONLY, "SDKSource")
        val proj = VegaProject("p1", "TestProject", "m1", listOf(m1, m2, m3))

        // 1. workspace:// 映射到 primary mount
        val p1 = ProjectPathParser.parse("workspace://src/Main.kt", proj)
        check(p1.mountAlias == "main")
        check(p1.relativePath == "src/Main.kt")
        check(p1.isPrimaryWorkspaceAlias)
        check(p1.toVirtualUri() == "workspace://src/Main.kt")

        // 2. project://shared/ 映射到对应 mount
        val p2 = ProjectPathParser.parse("project://shared/src/Foo.kt", proj)
        check(p2.mountAlias == "shared")
        check(p2.relativePath == "src/Foo.kt")
        check(!p2.isPrimaryWorkspaceAlias)
        check(p2.toVirtualUri() == "project://shared/src/Foo.kt")

        // 3. 根目录与空路径兼容
        val p3 = ProjectPathParser.parse("workspace://", proj)
        check(p3.mountAlias == "main" && p3.relativePath == "")
        val p4 = ProjectPathParser.parse("project://sdk/", proj)
        check(p4.mountAlias == "sdk" && p4.relativePath == "")

        // 4. 普通相对路径默认归入 primary
        val p5 = ProjectPathParser.parse("README.md", proj)
        check(p5.mountAlias == "main" && p5.relativePath == "README.md")

        // 5. 拒绝越权与路径穿越
        check(runCatching { ProjectPathParser.parse("project://shared/../../etc", proj) }.isFailure)
        check(runCatching { ProjectPathParser.parse("project://unknown/foo", proj) }.isFailure)
        check(runCatching { ProjectPathParser.parse("project://shared/%2e%2e/foo", proj) }.isFailure)

        // 6. 拒绝重叠 executionPath
        val overlap1 = WorkspaceMount("o1", "app", "content://a", "App", MountMode.READ_WRITE, "app")
        val overlap2 = WorkspaceMount("o2", "sub", "content://b", "Sub", MountMode.READ_WRITE, "app/vendor")
        check(runCatching { VegaProject("p_bad", "Bad", "o1", listOf(overlap1, overlap2)) }.isFailure)

        println("ProjectPathTests: All 12 checks passed successfully.")
    }
}
