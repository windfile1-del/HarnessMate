package dev.wind.agent.skills

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream

/** Explicit user imports, app-owned storage, and a read-only virtual namespace. No script execution. */
class SkillStore(private val root: File, private val builtin: () -> InputStream, private val extras: Map<String,()->InputStream>) {
    constructor(root: File,builtin: ()->InputStream): this(root,builtin,emptyMap())
    constructor(context: Context) : this(File(context.filesDir, "skills/installed"), {
        val file = File(context.filesDir, "skills/builtin/HarnessMate/SKILL.md")
        file.parentFile!!.mkdirs()
        context.assets.open("skills/HarnessMate.md").use { input -> file.outputStream().use { input.copyTo(it) } }
        file.inputStream()
    },BuiltinMateSkills.descriptions.keys.associateWith {id -> {
        val folder=File(context.filesDir,"skills/builtin").canonicalFile
        val file=File(folder,"$id/SKILL.md").canonicalFile
        require(file.path.startsWith(folder.path+File.separator));check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        context.assets.open("skills/builtin/$id/SKILL.md").use {input ->file.outputStream().use {input.copyTo(it)}}
        file.inputStream()
    }}) {
        // Preserve genuine installed skills from the former app-owned directory.
        val legacy = File(context.filesDir, "skills")
        legacy.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("skill-[0-9a-f-]{36}")) }?.forEach { dir ->
            runCatching {
                val metadata = JSONObject(File(dir, "entry.json").readText(Charsets.UTF_8))
                val main = File(dir, safeRelative(metadata.getString("main")))
                val header = main.inputStream().bufferedReader().use { it.readLine().orEmpty() }
                if (main.name == "SKILL.md" && header == "---" && !File(root, dir.name).exists()) {
                    root.mkdirs(); check(dir.renameTo(File(root, dir.name)))
                }
            }
        }
    }
    data class Entry(val id: String, val name: String, val description: String, val path: String, val builtin: Boolean,val preferredProfiles: List<String> = emptyList()) {
        fun json() = JSONObject().put("id", id).put("name", name).put("description", description)
            .put("path", path).put("builtin", builtin).put("preferred_profiles",JSONArray(preferredProfiles))
    }
    companion object {
        const val BUILTIN_PATH = "skills://builtin/HarnessMate/SKILL.md"
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
        private val LOCK = Any()
        fun safeRelative(path: String): String {
            require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
                path.none { it < ' ' } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "SKILL_PATH_INVALID" }
            return path
        }
    }
    fun directory(): String = root.absolutePath
    fun remove(id: String) = synchronized(LOCK) {
        require(id.matches(Regex("skill-[0-9a-f-]{36}")) && list().any { it.id == id }) { "SKILL_NOT_INSTALLED" }
        val target = File(root, id).canonicalFile
        require(target.parentFile == root.canonicalFile)
        File(root, ".deleted-$id").writeText("deleted")
        check(target.deleteRecursively()) { "SKILL_REMOVE_FAILED" }
    }
    fun list(): List<Entry> = synchronized(LOCK) {
        val entries = mutableListOf(Entry("builtin", "HarnessMate", "Harness 操作手册、工具与扩展包安装；按需读取。", BUILTIN_PATH, true))
        for(id in extras.keys.sorted()) {
            val description=BuiltinMateSkills.descriptions[id] ?: error("BUILTIN_SKILL_NOT_MANIFESTED")
            entries.add(Entry("builtin-$id",id,description,"skills://builtin/$id/SKILL.md",true,listOf("mate")))
        }
        root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("skill-[0-9a-f-]{36}")) }?.sortedBy { it.name }?.forEach { dir ->
            runCatching {
                val metadata = File(dir, "entry.json")
                require(metadata.length() in 1..16384)
                val j = JSONObject(metadata.readText(Charsets.UTF_8))
                val main = safeRelative(j.getString("main"))
                require(main.substringAfterLast('/') == "SKILL.md" && File(dir, main).isFile)
                entries.add(Entry(dir.name, j.getString("name").take(80), j.getString("description").take(512), "skills://${dir.name}/$main", false))
            }
        }
        entries
    }
    fun catalogJson(query: String="",offset: Int=0,limit: Int=20): JSONObject {
        require(query.length<=200 && offset in 0..100000 && limit in 1..20) {"SKILL_CATALOG_WINDOW_INVALID"}
        val terms=query.lowercase().split(Regex("\\s+")).filter {it.isNotBlank()}
        val entries=list().filter {entry ->terms.isEmpty() || terms.any {it in (entry.name+" "+entry.description).lowercase()}}
        return JSONObject().put("default_directory",directory()).put("skills",JSONArray(entries.drop(offset).take(limit).map {it.json()}))
            .put("offset",offset).put("limit",limit).put("has_more",offset.toLong()+limit<entries.size).put("total",entries.size)
    }

    fun read(path: String, startLine: Int = 1, maxLines: Int = 200): String {
        require(startLine in 1..100000 && maxLines in 1..400) { "SKILL_READ_WINDOW_INVALID" }
        val extra=extras.keys.firstOrNull {path=="skills://builtin/$it/SKILL.md"}
        val input = if (path == BUILTIN_PATH || path == "skills://builtin/HarnessMate.md") builtin() else if(extra!=null)extras.getValue(extra)() else {
            require(path.startsWith("skills://")) { "SKILL_PATH_INVALID" }
            val relative = safeRelative(path.removePrefix("skills://"))
            val id = relative.substringBefore('/')
            require(id.matches(Regex("skill-[0-9a-f-]{36}"))) { "SKILL_NOT_INSTALLED" }
            require(list().any { it.id == id }) { "SKILL_NOT_INSTALLED" }
            val dir = File(root, id).canonicalFile
            val file = File(root, relative).canonicalFile
            require(file.path.startsWith(dir.path + File.separator) && file.isFile && file.length() <= MAX_FILE_BYTES) { "SKILL_PATH_INVALID" }
            file.inputStream()
        }
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            var number = 0
            while (number < startLine + maxLines - 1) {
                // Bound a physical line before allocating an arbitrary-length Markdown/script line.
                val line = StringBuilder()
                var eof = false
                while (true) {
                    val ch = reader.read()
                    if (ch < 0) { eof = true; break }
                    if (ch == 10) break
                    require(line.length < 16384) { "SKILL_LINE_LIMIT" }
                    if (ch != 13) line.append(ch.toChar())
                }
                if (eof && line.isEmpty()) break
                number++
                if (number >= startLine) {
                    if (result.length + line.length + 20 > 65536) break
                    result.append(number).append(" | ").append(line).append('\n')
                }
                if (eof) break
            }
            result.toString()
        }
    }

    /** Input is selected by the user. ZIPs preserve references/assets as ordinary files, never links. */
    fun install(input: InputStream, filename: String): Entry = synchronized(LOCK) {
        check(root.isDirectory || root.mkdirs()) { "SKILL_STORAGE_UNAVAILABLE" }
        val id = "skill-" + UUID.randomUUID()
        val stage = File(root, ".stage-$id").apply { check(mkdir()) }
        try {
            val files = mutableListOf<String>()
            var total = 0L
            fun write(name: String, source: InputStream) {
                val relative = safeRelative(name)
                require(relative != "entry.json" && files.add(relative) && files.count { it == relative } == 1) { "SKILL_DUPLICATE_PATH" }
                val target = File(stage, relative)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = source.read(buffer); if (n < 0) break
                        size += n; total += n
                        require(size <= MAX_FILE_BYTES && total <= MAX_TOTAL_BYTES) { "SKILL_IMPORT_SIZE_LIMIT" }
                        output.write(buffer, 0, n)
                    }
                }
            }
            input.use { source ->
                if (filename.endsWith(".zip", true)) ZipInputStream(source).use { zip ->
                    var count = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++count <= 512) { "SKILL_IMPORT_ENTRY_LIMIT" }
                        if (entry.isDirectory) safeRelative(entry.name.trimEnd('/')) else write(entry.name, zip)
                        zip.closeEntry()
                    }
                } else {
                    require(filename == "SKILL.md") { "SKILL_ENTRY_REQUIRED: select SKILL.md or a skill ZIP; ordinary Markdown is not a skill" }
                    write("SKILL.md", source)
                }
            }
            val mains = files.filter { it.substringAfterLast('/') == "SKILL.md" }
            require(mains.size == 1) { "SKILL_ENTRY_REQUIRED: exactly one SKILL.md" }
            val main = mains.single()
            val prefix = main.substringBeforeLast('/', "")
            require(prefix.isEmpty() || files.all { it.startsWith("$prefix/") }) { "SKILL_MULTIPLE_ROOTS" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(File(stage, main).readBytes())).toString()
            require('\u0000' !in text && text.isNotBlank()) { "SKILL_TEXT_INVALID" }
            val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
            val header = if (normalized.startsWith("---\n")) normalized.substring(4).substringBefore("\n---").take(16384) else ""
            require(normalized.startsWith("---\n") && normalized.indexOf("\n---", 4) >= 4) { "SKILL_FRONTMATTER_REQUIRED" }
            fun field(key: String): String? {
                val lines = header.lines(); val index = lines.indexOfFirst { it.startsWith("$key:") }
                if (index < 0) return null
                val value = lines[index].substringAfter(':').trim().trim('"', '\'')
                return if (value in listOf("|", ">", "|-", ">-")) lines.drop(index + 1)
                    .takeWhile { it.startsWith(' ') || it.startsWith('\t') }.joinToString(" ") { it.trim() }.trim()
                else value
            }
            val name = (field("name") ?: error("SKILL_METADATA_REQUIRED: name")).take(80)
            val description = (field("description") ?: error("SKILL_METADATA_REQUIRED: description")).take(512)
            require(name.isNotBlank() && description.isNotBlank() && name.none { it < ' ' } && description.none { it < ' ' }) { "SKILL_METADATA_INVALID" }
            val metadata = JSONObject().put("main", main).put("name", name).put("description", description)
            File(stage, "entry.json").writeText(metadata.toString(), Charsets.UTF_8)
            check(stage.renameTo(File(root, id))) { "SKILL_INSTALL_COMMIT_FAILED" }
            Entry(id, name, description, "skills://$id/$main", false)
        } finally { if (stage.exists()) stage.deleteRecursively() }
    }
}
