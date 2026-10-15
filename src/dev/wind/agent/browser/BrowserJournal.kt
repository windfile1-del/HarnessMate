package dev.wind.agent.browser

import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Only session references and hashed action identities survive process death. */
internal class BrowserJournal(root: File) {
    private val directory = File(root, "browser-journal").apply { mkdirs() }
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("br_[a-f0-9]{32}"))) { "BROWSER_SESSION_ID_INVALID" }
        return AtomicFile(File(directory, "$id.json"))
    }
    @Synchronized fun read(id: String): JSONObject? {
        val f = file(id)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return null
        // A corrupt journal must never silently reset an uncertain action.
        return try { f.openRead().use { input ->
            require(input.available() <= 196608) { "BROWSER_JOURNAL_TOO_LARGE" }
            JSONObject(input.bufferedReader(Charsets.UTF_8).readText())
        } } catch (_: Exception) { error("BROWSER_JOURNAL_REVIEW_REQUIRED") }
    }
    @Synchronized fun write(id: String, data: JSONObject) {
        val f = file(id); val bytes = data.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 196608) { "BROWSER_JOURNAL_TOO_LARGE" }
        val out = f.startWrite()
        try { out.write(bytes); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }
    fun ids(): List<String> = directory.listFiles().orEmpty().mapNotNull {
        it.name.removeSuffix(".json").takeIf { id -> it.name.endsWith(".json") && id.matches(Regex("br_[a-f0-9]{32}")) }
    }.take(64)
}
