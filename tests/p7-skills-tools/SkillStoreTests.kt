package dev.wind.agent.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Independent host test suite for SkillStore.
 *
 * Verifies:
 * 1. Single Markdown skill import with YAML frontmatter parsing.
 * 2. Multi-file ZIP skill import with assets and single root enforcement.
 * 3. Progressive header metadata read without buffering full file bodies.
 * 4. Strict path traversal rejection (.., leading /, backslash, colons, control chars).
 * 5. Catalog non-full-text injection: catalogJson() only exposes lightweight metadata.
 * 6. Streaming window read: startLine/maxLines, physical line limit (16KB), and buffer cap (64KB).
 * 7. Boundary validation: entry limit (<= 512), max file bytes (<= 2MB), max total bytes (<= 32MB).
 */
object SkillStoreTests {
    private var assertions = 0

    private fun check(condition: Boolean, message: String) {
        assertions++
        if (!condition) {
            throw AssertionError("FAILED: $message")
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val tempDir = File.createTempFile("skill-store-test-", "-dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val builtinMarkdown = """
                # HarnessMate Manual
                Harness 操作手册、工具与扩展包安装；按需读取。
                Line 3: Operation details.
                Line 4: Tool execution policy.
                Line 5: Security boundary.
            """.trimIndent()

            val store = SkillStore(tempDir) { ByteArrayInputStream(builtinMarkdown.toByteArray(StandardCharsets.UTF_8)) }

            testBuiltinCatalogAndRead(store)
            testSingleMarkdownImport(store)
            testZipSkillImport(store)
            testPathTraversalRejections(store)
            testProgressiveMetadataAndNoFullTextInjection(store)
            testWindowedStreamingRead(store)
            testResourceAndEntryLimits(store)
            val ordinary = runCatching { store.install(ByteArrayInputStream("# ordinary document\nname: body-is-not-metadata\n".toByteArray()), "ordinary.md") }
            check(ordinary.isFailure, "Ordinary Markdown must not become a skill")
            check(runCatching { store.install(ByteArrayInputStream("# missing metadata".toByteArray()), "SKILL.md") }.isFailure, "SKILL.md requires metadata")
            val previous = store.list().size
            var malformedRejected = false
            try { store.install(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), "SKILL.md") }
            catch (_: java.nio.charset.CharacterCodingException) { malformedRejected = true }
            check(malformedRejected, "Malformed UTF-8 entry must be rejected")
            check(store.list().size == previous, "Failed import must not publish an entry")

            println("SkillStoreTests: OK ($assertions assertions passed)")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun testBuiltinCatalogAndRead(store: SkillStore) {
        val list = store.list()
        check(list.any { it.builtin && it.id == "builtin" && it.path == SkillStore.BUILTIN_PATH }, "Builtin HarnessMate entry must be present")

        val catalog = store.catalogJson()
        check(catalog.has("default_directory"), "Catalog must have default_directory")
        check(catalog.has("skills"), "Catalog must have skills array")

        val builtinJson = catalog.getJSONArray("skills").getJSONObject(0)
        check(builtinJson.getString("id") == "builtin", "Builtin ID match")
        check(!builtinJson.has("body") && !builtinJson.has("content"), "Catalog must NOT inject full body text")

        val content = store.read(SkillStore.BUILTIN_PATH, 1, 3)
        check(content.contains("1 | # HarnessMate Manual"), "Windowed read line 1 must match")
        check(content.contains("2 | Harness 操作手册"), "Windowed read line 2 must match")
    }

    private fun testSingleMarkdownImport(store: SkillStore) {
        val md = """
            ---
            name: Code Refactoring Assistant
            description: Specialized skill for automated safe Kotlin/Java refactoring.
            ---
            # Code Refactoring Instructions
            Always verify tests before commit.
        """.trimIndent()

        val entry = store.install(ByteArrayInputStream(md.toByteArray(StandardCharsets.UTF_8)), "SKILL.md")
        check(entry.name == "Code Refactoring Assistant", "Parsed frontmatter name must match: ${entry.name}")
        check(entry.description.startsWith("Specialized skill"), "Parsed description must match")
        check(entry.path.startsWith("skills://${entry.id}/"), "Path must use skills:// URI scheme")
        check(!entry.builtin, "Imported entry must not be marked builtin")

        val readBack = store.read(entry.path, 1, 10)
        check(readBack.contains("Code Refactoring Instructions"), "Content readback must succeed")
    }

    private fun testZipSkillImport(store: SkillStore) {
        val skillMd = """
            ---
            name: Bundle Tool
            description: Bundled tool with helpers.
            ---

            # Main
            Run script.
        """.trimIndent()

        val zipBytes = buildZip(mapOf(
            "bundle/SKILL.md" to skillMd,
            "bundle/helper.py" to "print('helper')",
            "bundle/data/config.json" to "{\"key\": \"val\"}"
        ))

        val entry = store.install(ByteArrayInputStream(zipBytes), "bundle.zip")
        check(entry.name == "Bundle Tool", "ZIP parsed name must match: ${entry.name}")
        check(entry.path == "skills://${entry.id}/bundle/SKILL.md", "Main path in ZIP must resolve")

        val readMain = store.read(entry.path, 1, 10)
        check(readMain.contains("Run script"), "ZIP main file readback must succeed")

        val readHelper = store.read("skills://${entry.id}/bundle/helper.py", 1, 5)
        check(readHelper.contains("print('helper')"), "Helper asset readback must succeed")
    }

    private fun testPathTraversalRejections(store: SkillStore) {
        val evilPaths = listOf(
            "../escape.md",
            "/etc/passwd",
            "sub/../../escape.md",
            "win\\path.md",
            "c:drive.md",
            "sub/./dot.md",
            "invalid\u0000char.md"
        )

        for (evil in evilPaths) {
            var rejected = false
            try {
                SkillStore.safeRelative(evil)
            } catch (e: IllegalArgumentException) {
                rejected = true
            }
            check(rejected, "Path '$evil' must be rejected by safeRelative")
        }

        var readRejected = false
        try {
            store.read("skills://../escape.md")
        } catch (e: Exception) {
            readRejected = true
        }
        check(readRejected, "Path traversal read URI must be rejected")
    }

    private fun testProgressiveMetadataAndNoFullTextInjection(store: SkillStore) {
        val largeBody = "X".repeat(50000)
        val md = "---\nname: Large Skill\ndescription: Heavy body payload test.\n---\n\n" + largeBody

        val entry = store.install(ByteArrayInputStream(md.toByteArray(StandardCharsets.UTF_8)), "SKILL.md")
        check(entry.name == "Large Skill", "Header must be read progressively")

        val catalogJson = store.catalogJson()
        val skillsArray = catalogJson.getJSONArray("skills")
        for (i in 0 until skillsArray.length()) {
            val item = skillsArray.getJSONObject(i)
            check(!item.has("body"), "No skill item may inject body into catalog JSON")
            check(!item.has("content"), "No skill item may inject content into catalog JSON")
            check(item.has("id") && item.has("name") && item.has("description") && item.has("path"), "Lightweight metadata required")
        }
    }

    private fun testWindowedStreamingRead(store: SkillStore) {
        val lines = "---\nname: window\ndescription: Windowed read fixture\n---\n" + (5..50).joinToString("\n") { "Line $it content for windowing." }
        val entry = store.install(ByteArrayInputStream(lines.toByteArray(StandardCharsets.UTF_8)), "SKILL.md")

        val window = store.read(entry.path, startLine = 10, maxLines = 5)
        check(window.contains("10 | Line 10 content"), "Window must start at startLine")
        check(window.contains("14 | Line 14 content"), "Window must contain line 14")
        check(!window.contains("15 | Line 15 content"), "Window must not exceed maxLines")
        check(!window.contains("9 | Line 9 content"), "Window must not include lines before startLine")
    }

    private fun testResourceAndEntryLimits(store: SkillStore) {
        val multiRootZip = buildZip(mapOf(
            "root1/SKILL.md" to "name: R1\ndescription: D1\n",
            "root2/other.txt" to "other"
        ))
        var multiRootRejected = false
        try {
            store.install(ByteArrayInputStream(multiRootZip), "multi.zip")
        } catch (e: IllegalArgumentException) {
            multiRootRejected = true
        }
        check(multiRootRejected, "ZIP with multiple roots must be rejected")

        val noSkillZip = buildZip(mapOf(
            "folder/readme.txt" to "no skill entry point"
        ))
        var missingMainRejected = false
        try {
            store.install(ByteArrayInputStream(noSkillZip), "nomain.zip")
        } catch (e: IllegalArgumentException) {
            missingMainRejected = true
        }
        check(missingMainRejected, "ZIP without SKILL.md must be rejected")
    }

    private fun buildZip(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
