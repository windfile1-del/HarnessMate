package dev.wind.agent.intelligence.index

import dev.wind.agent.CancellationToken
import dev.wind.agent.intelligence.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object StaticParsers {
    fun parse(relativePath: String, content: String): FileParseResult {
        val lang = CodeLanguage.fromPath(relativePath)
        val sha256 = sha256Hex(content.toByteArray(Charsets.UTF_8))
        if (content.length > CodeIntelLimits.MAX_INDEX_SOURCE_FILE) {
            return FileParseResult(relativePath, lang, sha256, emptyList(), emptyList(), emptyList())
        }

        return when (lang) {
            CodeLanguage.KOTLIN -> parseKotlin(relativePath, content, sha256)
            CodeLanguage.JAVA -> parseJava(relativePath, content, sha256)
            CodeLanguage.PYTHON -> parsePython(relativePath, content, sha256)
            CodeLanguage.TYPESCRIPT, CodeLanguage.JAVASCRIPT -> parseTsJs(relativePath, content, sha256, lang)
            CodeLanguage.UNKNOWN -> FileParseResult(relativePath, lang, sha256, emptyList(), emptyList(), emptyList())
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun parseKotlin(path: String, content: String, sha256: String): FileParseResult {
        val symbols = mutableListOf<ParsedSymbol>()
        val references = mutableListOf<ParsedReference>()
        val imports = mutableListOf<ParsedImport>()
        val lines = content.lines()
        var currentPackage = ""
        var currentContainer: String? = null

        val packageRegex = Regex("""^\s*package\s+([a-zA-Z0-9_.]+)""")
        val importRegex = Regex("""^\s*import\s+([a-zA-Z0-9_.*]+)(?:\s+as\s+([a-zA-Z0-9_]+))?""")
        val classRegex = Regex("""\b(class|interface|object|enum\s+class)\s+([a-zA-Z0-9_]+)""")
        val funRegex = Regex("""\b(fun)\s+(?:<[^>]+>\s*)?(?:([a-zA-Z0-9_.]+)\.)?([a-zA-Z0-9_]+)\s*\(""")
        val valRegex = Regex("""\b(val|var)\s+([a-zA-Z0-9_]+)""")
        val typealiasRegex = Regex("""\btypealias\s+([a-zA-Z0-9_]+)""")

        for (lineIdx in lines.indices) {
            val rawLine = lines[lineIdx]
            val lineNum = lineIdx + 1
            val clean = stripCommentsAndStrings(rawLine, isPython = false)

            packageRegex.find(clean)?.let {
                currentPackage = it.groupValues[1]
            }

            importRegex.find(clean)?.let {
                val full = it.groupValues[1]
                val alias = it.groupValues.getOrNull(2)?.takeIf { a -> a.isNotBlank() }
                val isWild = full.endsWith(".*")
                val mod = if (isWild) full.removeSuffix(".*") else full.substringBeforeLast('.', full)
                val item = if (isWild) null else full.substringAfterLast('.')
                imports.add(ParsedImport(mod, item, alias, isWild))
            }

            classRegex.findAll(clean).forEach { match ->
                val kindStr = match.groupValues[1]
                val name = match.groupValues[2]
                val kind = when {
                    kindStr.contains("interface") -> SymbolKind.INTERFACE
                    kindStr.contains("enum") -> SymbolKind.ENUM
                    kindStr.contains("object") -> SymbolKind.CLASS
                    else -> SymbolKind.CLASS
                }
                val col = match.range.first + 1
                val qname = if (currentPackage.isEmpty()) name else "$currentPackage.$name"
                currentContainer = name
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = qname,
                    kind = kind,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    containerName = null,
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            funRegex.findAll(clean).forEach { match ->
                val receiver = match.groupValues[2].takeIf { it.isNotEmpty() }
                val name = match.groupValues[3]
                val col = match.range.first + 1
                val container = receiver ?: currentContainer
                val qname = when {
                    container != null -> "$container.$name"
                    currentPackage.isNotEmpty() -> "$currentPackage.$name"
                    else -> name
                }
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = qname,
                    kind = if (container != null) SymbolKind.METHOD else SymbolKind.FUNCTION,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    containerName = container,
                    signature = match.value.trim(),
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            valRegex.findAll(clean).forEach { match ->
                val name = match.groupValues[2]
                val col = match.range.first + 1
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = if (currentContainer != null) "$currentContainer.$name" else name,
                    kind = if (currentContainer != null) SymbolKind.FIELD else SymbolKind.VARIABLE,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    containerName = currentContainer,
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            typealiasRegex.findAll(clean).forEach { match ->
                val name = match.groupValues[1]
                val col = match.range.first + 1
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = name,
                    kind = SymbolKind.TYPE,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            extractIdentifierReferences(clean, lineNum, references)
        }

        return FileParseResult(
            relativePath = path,
            language = CodeLanguage.KOTLIN,
            sha256 = sha256,
            symbols = symbols.take(CodeIntelLimits.MAX_SYMBOLS_PER_FILE),
            references = references.take(CodeIntelLimits.MAX_REFERENCES_PER_FILE),
            imports = imports
        )
    }

    private fun parseJava(path: String, content: String, sha256: String): FileParseResult {
        val symbols = mutableListOf<ParsedSymbol>()
        val references = mutableListOf<ParsedReference>()
        val imports = mutableListOf<ParsedImport>()
        val lines = content.lines()
        var currentPackage = ""
        var currentContainer: String? = null

        val packageRegex = Regex("""^\s*package\s+([a-zA-Z0-9_.]+);""")
        val importRegex = Regex("""^\s*import\s+(?:static\s+)?([a-zA-Z0-9_.*]+);""")
        val classRegex = Regex("""\b(class|interface|enum|record|@interface)\s+([a-zA-Z0-9_]+)""")
        val methodRegex = Regex("""(?:public|protected|private|static|final|synchronized|abstract|default)\s+[\w<>\[\],\s]+\s+([a-zA-Z0-9_]+)\s*\([^)]*\)""")
        val fieldRegex = Regex("""(?:public|protected|private|static|final)\s+[\w<>\[\]]+\s+([a-zA-Z0-9_]+)\s*(?:=|;)""")

        for (lineIdx in lines.indices) {
            val rawLine = lines[lineIdx]
            val lineNum = lineIdx + 1
            val clean = stripCommentsAndStrings(rawLine, isPython = false)

            packageRegex.find(clean)?.let { currentPackage = it.groupValues[1] }

            importRegex.find(clean)?.let {
                val full = it.groupValues[1]
                val isWild = full.endsWith(".*")
                val mod = if (isWild) full.removeSuffix(".*") else full.substringBeforeLast('.', full)
                val item = if (isWild) null else full.substringAfterLast('.')
                imports.add(ParsedImport(mod, item, null, isWild))
            }

            classRegex.findAll(clean).forEach { match ->
                val kindStr = match.groupValues[1]
                val name = match.groupValues[2]
                val kind = when {
                    kindStr == "interface" || kindStr == "@interface" -> SymbolKind.INTERFACE
                    kindStr == "enum" -> SymbolKind.ENUM
                    else -> SymbolKind.CLASS
                }
                currentContainer = name
                val col = match.range.first + 1
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = if (currentPackage.isEmpty()) name else "$currentPackage.$name",
                    kind = kind,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    containerName = null,
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            methodRegex.findAll(clean).forEach { match ->
                val name = match.groupValues[1]
                if (name != "if" && name != "for" && name != "while" && name != "switch") {
                    val col = match.range.first + 1
                    symbols.add(ParsedSymbol(
                        name = name,
                        qualifiedName = if (currentContainer != null) "$currentContainer.$name" else name,
                        kind = SymbolKind.METHOD,
                        range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                        containerName = currentContainer,
                        signature = match.value.trim(),
                        confidence = IntelligenceConfidence.LEXICAL
                    ))
                }
            }

            fieldRegex.findAll(clean).forEach { match ->
                val name = match.groupValues[1]
                val col = match.range.first + 1
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = if (currentContainer != null) "$currentContainer.$name" else name,
                    kind = SymbolKind.FIELD,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    containerName = currentContainer,
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            extractIdentifierReferences(clean, lineNum, references)
        }

        return FileParseResult(
            relativePath = path,
            language = CodeLanguage.JAVA,
            sha256 = sha256,
            symbols = symbols.take(CodeIntelLimits.MAX_SYMBOLS_PER_FILE),
            references = references.take(CodeIntelLimits.MAX_REFERENCES_PER_FILE),
            imports = imports
        )
    }

    private fun parsePython(path: String, content: String, sha256: String): FileParseResult {
        val symbols = mutableListOf<ParsedSymbol>()
        val references = mutableListOf<ParsedReference>()
        val imports = mutableListOf<ParsedImport>()
        val lines = content.lines()
        val scopeStack = mutableListOf<Pair<Int, String>>() // indent to class name

        val classRegex = Regex("""^(\s*)class\s+([a-zA-Z0-9_]+)""")
        val defRegex = Regex("""^(\s*)(?:async\s+)?def\s+([a-zA-Z0-9_]+)\s*\(""")
        val importRegex = Regex("""^\s*import\s+([a-zA-Z0-9_.,\s]+)""")
        val fromImportRegex = Regex("""^\s*from\s+([a-zA-Z0-9_.]+)\s+import\s+([a-zA-Z0-9_*,\s]+)""")
        val assignRegex = Regex("""^([a-zA-Z0-9_]+)\s*=\s*""")

        for (lineIdx in lines.indices) {
            val rawLine = lines[lineIdx]
            val lineNum = lineIdx + 1
            val clean = stripCommentsAndStrings(rawLine, isPython = true)
            if (clean.isBlank()) continue

            val indent = rawLine.takeWhile { it == ' ' || it == '\t' }.length
            while (scopeStack.isNotEmpty() && scopeStack.last().first >= indent) {
                scopeStack.removeAt(scopeStack.size - 1)
            }
            val currentClass = scopeStack.lastOrNull()?.second

            importRegex.find(clean)?.let { match ->
                match.groupValues[1].split(',').forEach { item ->
                    val trimmed = item.trim().split(Regex("""\s+as\s+"""))
                    val mod = trimmed[0]
                    val alias = trimmed.getOrNull(1)
                    imports.add(ParsedImport(mod, null, alias))
                }
            }

            fromImportRegex.find(clean)?.let { match ->
                val mod = match.groupValues[1]
                match.groupValues[2].split(',').forEach { item ->
                    val trimmed = item.trim().split(Regex("""\s+as\s+"""))
                    val name = trimmed[0]
                    val alias = trimmed.getOrNull(1)
                    imports.add(ParsedImport(mod, name, alias, isWildcard = name == "*"))
                }
            }

            classRegex.find(clean)?.let { match ->
                val name = match.groupValues[2]
                val col = match.range.first + 1
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = name,
                    kind = SymbolKind.CLASS,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    confidence = IntelligenceConfidence.LEXICAL
                ))
                scopeStack.add(indent to name)
            }

            defRegex.find(clean)?.let { match ->
                val name = match.groupValues[2]
                val col = match.range.first + 1
                val kind = if (currentClass != null) SymbolKind.METHOD else SymbolKind.FUNCTION
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = if (currentClass != null) "$currentClass.$name" else name,
                    kind = kind,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    containerName = currentClass,
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            if (indent == 0) {
                assignRegex.find(clean)?.let { match ->
                    val name = match.groupValues[1]
                    symbols.add(ParsedSymbol(
                        name = name,
                        qualifiedName = name,
                        kind = SymbolKind.VARIABLE,
                        range = CodeRange(CodePosition(lineNum, 1), CodePosition(lineNum, 1 + name.length)),
                        confidence = IntelligenceConfidence.LEXICAL
                    ))
                }
            }

            extractIdentifierReferences(clean, lineNum, references)
        }

        return FileParseResult(
            relativePath = path,
            language = CodeLanguage.PYTHON,
            sha256 = sha256,
            symbols = symbols.take(CodeIntelLimits.MAX_SYMBOLS_PER_FILE),
            references = references.take(CodeIntelLimits.MAX_REFERENCES_PER_FILE),
            imports = imports
        )
    }

    private fun parseTsJs(path: String, content: String, sha256: String, lang: CodeLanguage): FileParseResult {
        val symbols = mutableListOf<ParsedSymbol>()
        val references = mutableListOf<ParsedReference>()
        val imports = mutableListOf<ParsedImport>()
        val lines = content.lines()
        var currentContainer: String? = null

        val importRegex = Regex("""^\s*import\s+.*?from\s+['"](.*?)['"]""")
        val requireRegex = Regex("""require\(['"](.*?)['"]\)""")
        val classRegex = Regex("""\b(class|interface|type|enum)\s+([a-zA-Z0-9_]+)""")
        val funRegex = Regex("""\b(?:async\s+)?function\s+([a-zA-Z0-9_]+)\s*\(""")
        val constFunRegex = Regex("""\b(const|let|var)\s+([a-zA-Z0-9_]+)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[a-zA-Z0-9_]+)\s*=>""")
        val varRegex = Regex("""\b(const|let|var)\s+([a-zA-Z0-9_]+)\s*=""")
        val methodRegex = Regex("""\b([a-zA-Z0-9_]+)\s*\([^)]*\)\s*\{""")

        for (lineIdx in lines.indices) {
            val rawLine = lines[lineIdx]
            val lineNum = lineIdx + 1
            val clean = stripCommentsAndStrings(rawLine, isPython = false)

            importRegex.find(clean)?.let {
                imports.add(ParsedImport(it.groupValues[1]))
            }
            requireRegex.findAll(clean).forEach {
                imports.add(ParsedImport(it.groupValues[1]))
            }

            classRegex.findAll(clean).forEach { match ->
                val kindStr = match.groupValues[1]
                val name = match.groupValues[2]
                val kind = when (kindStr) {
                    "interface" -> SymbolKind.INTERFACE
                    "type" -> SymbolKind.TYPE
                    "enum" -> SymbolKind.ENUM
                    else -> SymbolKind.CLASS
                }
                currentContainer = name
                val col = match.range.first + 1
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = name,
                    kind = kind,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            if (currentContainer != null) {
                methodRegex.findAll(clean).forEach { match ->
                    val name = match.groupValues[1]
                    if (name !in setOf("if", "for", "while", "switch", "catch", "constructor", "function")) {
                        val col = match.range.first + 1
                        symbols.add(ParsedSymbol(
                            name = name,
                            qualifiedName = "$currentContainer.$name",
                            kind = SymbolKind.METHOD,
                            range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                            containerName = currentContainer,
                            confidence = IntelligenceConfidence.LEXICAL
                        ))
                    }
                }
            }

            funRegex.findAll(clean).forEach { match ->
                val name = match.groupValues[1]
                val col = match.range.first + 1
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = if (currentContainer != null) "$currentContainer.$name" else name,
                    kind = if (currentContainer != null) SymbolKind.METHOD else SymbolKind.FUNCTION,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    containerName = currentContainer,
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            constFunRegex.findAll(clean).forEach { match ->
                val name = match.groupValues[2]
                val col = match.range.first + 1
                symbols.add(ParsedSymbol(
                    name = name,
                    qualifiedName = if (currentContainer != null) "$currentContainer.$name" else name,
                    kind = SymbolKind.FUNCTION,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    containerName = currentContainer,
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }

            varRegex.findAll(clean).forEach { match ->
                val name = match.groupValues[2]
                if (symbols.none { it.range.start.line == lineNum && it.name == name }) {
                    val col = match.range.first + 1
                    symbols.add(ParsedSymbol(
                        name = name,
                        qualifiedName = if (currentContainer != null) "$currentContainer.$name" else name,
                        kind = SymbolKind.VARIABLE,
                        range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                        containerName = currentContainer,
                        confidence = IntelligenceConfidence.LEXICAL
                    ))
                }
            }

            extractIdentifierReferences(clean, lineNum, references)
        }

        return FileParseResult(
            relativePath = path,
            language = lang,
            sha256 = sha256,
            symbols = symbols.take(CodeIntelLimits.MAX_SYMBOLS_PER_FILE),
            references = references.take(CodeIntelLimits.MAX_REFERENCES_PER_FILE),
            imports = imports
        )
    }

    private fun stripCommentsAndStrings(line: String, isPython: Boolean): String {
        val sb = java.lang.StringBuilder()
        var inString = false
        var stringChar = ' '
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inString) {
                if (c == stringChar && (i == 0 || line[i - 1] != '\\')) {
                    inString = false
                }
                sb.append(' ')
            } else {
                if (!isPython && c == '/' && i + 1 < line.length && line[i + 1] == '/') {
                    break
                }
                if (isPython && c == '#') {
                    break
                }
                if (c == '"' || c == '\'' || (!isPython && c == '`')) {
                    inString = true
                    stringChar = c
                    sb.append(' ')
                } else {
                    sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun extractIdentifierReferences(
        cleanLine: String,
        lineNum: Int,
        references: MutableList<ParsedReference>
    ) {
        val identRegex = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\b""")
        val keywords = setOf(
            "class", "interface", "fun", "val", "var", "def", "package", "import", "return",
            "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
            "public", "private", "protected", "static", "final", "abstract", "const", "let",
            "null", "true", "false", "new", "this", "super", "try", "catch", "finally", "throw",
            "object", "typealias", "enum", "record", "async", "await", "from", "as", "in"
        )
        identRegex.findAll(cleanLine).forEach { match ->
            val name = match.groupValues[1]
            if (name !in keywords && name.length >= 2) {
                val col = match.range.first + 1
                references.add(ParsedReference(
                    name = name,
                    range = CodeRange(CodePosition(lineNum, col), CodePosition(lineNum, col + name.length)),
                    confidence = IntelligenceConfidence.LEXICAL
                ))
            }
        }
    }
}

class MemoryCodeIndexBackend : CodeIndexBackend {
    private val snapshots = ConcurrentHashMap<String, InMemorySnapshot>()
    private val candidates = ConcurrentHashMap<String, InMemorySnapshot>()
    // ConcurrentHashMap.newKeySet is API 24+; this app floors at minSdk 23.
    private val dirtyProjects: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    data class InMemorySnapshot(
        val snapshotId: CodeSnapshotId,
        val files: Map<String, FileParseResult>,
        val fileHashes: Map<String, String>,
        val builtAt: Long = System.currentTimeMillis()
    )

    override fun status(revision: SourceRevision): CodeIndexStatus {
        if (dirtyProjects.contains(revision.projectId)) {
            return CodeIndexStatus(phase = CodeIndexPhase.DIRTY, message = "Index marked dirty by mutation")
        }
        val snap = snapshots[revision.toSnapshotId().toKey()]
            ?: return CodeIndexStatus(phase = CodeIndexPhase.ABSENT, message = "No index built for this snapshot")
        return CodeIndexStatus(
            phase = CodeIndexPhase.READY,
            boundDigest = snap.snapshotId.sourceDigest,
            boundEpoch = revision.mutationEpoch,
            quality = "LEXICAL",
            fileCount = snap.files.size,
            symbolCount = snap.files.values.sumOf { it.symbols.size }
        )
    }

    override fun buildCandidate(
        candidateId: String,
        revision: SourceRevision,
        fileReader: (String) -> ByteArray?,
        manifestFiles: List<String>,
        token: CancellationToken
    ): Boolean {
        candidates.remove(candidateId)
        val previous = snapshots.values.firstOrNull { it.snapshotId.projectId == revision.projectId }
        val newFiles = mutableMapOf<String, FileParseResult>()
        val newHashes = mutableMapOf<String, String>()

        for (path in manifestFiles) {
            if (token.isCancelled) {
                candidates.remove(candidateId)
                return false
            }
            val bytes = fileReader(path) ?: continue
            // Enforce the cap on raw bytes before any UTF-8 decoding.  A
            // multi-byte payload must not bypass the safety bound via the
            // String/character count used by the parser.
            if (bytes.size.toLong() > CodeIntelLimits.MAX_INDEX_SOURCE_FILE) continue
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(bytes).joinToString("") { "%02x".format(it) }
            newHashes[path] = hash

            if (previous != null && previous.fileHashes[path] == hash) {
                previous.files[path]?.let { newFiles[path] = it }
            } else {
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: ""
                newFiles[path] = StaticParsers.parse(path, text)
            }
        }

        if (token.isCancelled) {
            candidates.remove(candidateId)
            return false
        }

        candidates[candidateId] = InMemorySnapshot(
            snapshotId = revision.toSnapshotId(),
            files = newFiles,
            fileHashes = newHashes
        )
        return true
    }

    override fun buildCandidateWithManifest(
        candidateId: String,
        revision: SourceRevision,
        fileReader: (String) -> ByteArray?,
        manifestFiles: List<IndexManifestFile>,
        token: CancellationToken
    ): Boolean {
        val checked = mutableMapOf<String, ByteArray>()
        for (entry in manifestFiles) {
            if (token.isCancelled) return false
            if (entry.size > CodeIntelLimits.MAX_INDEX_SOURCE_FILE) continue
            val bytes = fileReader(entry.path) ?: return false
            if (bytes.size.toLong() != entry.size) return false
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (entry.sha256 != null && !entry.sha256.equals(actual, ignoreCase = true)) return false
            checked[entry.path] = bytes
        }
        return buildCandidate(candidateId, revision, { checked[it] }, checked.keys.toList(), token)
    }

    override fun commitCandidate(
        candidateId: String,
        revisionBefore: SourceRevision,
        revisionAfter: SourceRevision
    ): Boolean {
        val cand = candidates.remove(candidateId) ?: return false
        if (revisionBefore != revisionAfter) {
            return false
        }
        val snapKey = revisionAfter.toSnapshotId().toKey()
        snapshots[snapKey] = cand

        val projectSnaps = snapshots.filter { it.value.snapshotId.projectId == revisionAfter.projectId }
        if (projectSnaps.size > 2) {
            val oldest = projectSnaps.minByOrNull { it.value.builtAt }
            if (oldest != null && oldest.key != snapKey) {
                snapshots.remove(oldest.key)
            }
        }
        dirtyProjects.remove(revisionAfter.projectId)
        return true
    }

    override fun discardCandidate(candidateId: String) {
        candidates.remove(candidateId)
    }

    override fun ensureFresh(
        revision: SourceRevision,
        fileReader: (String) -> ByteArray?,
        manifestFiles: List<String>,
        token: CancellationToken
    ): Boolean {
        dirtyProjects.remove(revision.projectId)
        val snapKey = revision.toSnapshotId().toKey()
        if (snapshots.containsKey(snapKey)) return true
        val candId = "cand-" + java.util.UUID.randomUUID().toString()
        val built = buildCandidate(candId, revision, fileReader, manifestFiles, token)
        if (!built) return false
        return commitCandidate(candId, revision, revision)
    }

    override fun searchSymbols(
        query: String,
        kinds: Set<SymbolKind>,
        language: CodeLanguage?,
        pathPrefix: String?,
        limit: Int,
        offset: Int,
        revision: SourceRevision
    ): List<SymbolLocation> {
        val snap = snapshots[revision.toSnapshotId().toKey()] ?: return emptyList()
        val qLower = query.lowercase().trim()
        val results = mutableListOf<SymbolLocation>()

        val effectiveLimit = limit.coerceIn(1, CodeIntelLimits.MAX_SYMBOL_RESULTS)
        var skipped = 0

        for ((filePath, parseResult) in snap.files) {
            if (language != null && parseResult.language != language) continue
            if (pathPrefix != null && !filePath.startsWith(pathPrefix)) continue

            for (sym in parseResult.symbols) {
                if (kinds.isNotEmpty() && sym.kind !in kinds) continue
                val nameMatch = qLower.isEmpty() || sym.name.lowercase().contains(qLower) || sym.qualifiedName.lowercase().contains(qLower)
                if (nameMatch) {
                    if (skipped < offset) {
                        skipped++
                        continue
                    }
                    results.add(SymbolLocation(
                        path = filePath,
                        range = sym.range,
                        symbol = sym.name,
                        kind = sym.kind,
                        confidence = sym.confidence,
                        containerName = sym.containerName,
                        signature = sym.signature,
                        symbolId = "sym_" + sym.qualifiedName.hashCode()
                    ))
                    if (results.size >= effectiveLimit) return results
                }
            }
        }
        return results
    }

    override fun findDefinitions(
        path: String?,
        line: Int?,
        column: Int?,
        symbolId: String?,
        revision: SourceRevision
    ): List<SymbolLocation> {
        val snap = snapshots[revision.toSnapshotId().toKey()] ?: return emptyList()
        val results = mutableListOf<SymbolLocation>()
        val maxDefs = CodeIntelLimits.MAX_DEFINITIONS

        var targetName: String? = null
        if (path != null && line != null) {
            val file = snap.files[path]
            if (file != null) {
                val symAtPos = file.symbols.firstOrNull {
                    it.range.start.line == line && (column == null || (it.range.start.column <= column && column <= it.range.end.column))
                }
                if (symAtPos != null) {
                    return listOf(SymbolLocation(
                        path = path,
                        range = symAtPos.range,
                        symbol = symAtPos.name,
                        kind = symAtPos.kind,
                        confidence = symAtPos.confidence,
                        containerName = symAtPos.containerName,
                        signature = symAtPos.signature,
                        symbolId = "sym_" + symAtPos.qualifiedName.hashCode()
                    ))
                }
                val refAtPos = file.references.firstOrNull {
                    it.range.start.line == line && (column == null || (it.range.start.column <= column && column <= it.range.end.column))
                }
                if (refAtPos != null) {
                    targetName = refAtPos.name
                }
            }
        }

        if (targetName == null && symbolId != null) {
            for ((filePath, parseResult) in snap.files) {
                for (sym in parseResult.symbols) {
                    if ("sym_" + sym.qualifiedName.hashCode() == symbolId) {
                        results.add(SymbolLocation(
                            path = filePath,
                            range = sym.range,
                            symbol = sym.name,
                            kind = sym.kind,
                            confidence = sym.confidence,
                            containerName = sym.containerName,
                            signature = sym.signature,
                            symbolId = symbolId
                        ))
                        if (results.size >= maxDefs) return results
                    }
                }
            }
            if (results.isNotEmpty()) return results
        }

        if (targetName != null) {
            for ((filePath, parseResult) in snap.files) {
                for (sym in parseResult.symbols) {
                    if (sym.name == targetName) {
                        results.add(SymbolLocation(
                            path = filePath,
                            range = sym.range,
                            symbol = sym.name,
                            kind = sym.kind,
                            confidence = sym.confidence,
                            containerName = sym.containerName,
                            signature = sym.signature,
                            symbolId = "sym_" + sym.qualifiedName.hashCode()
                        ))
                        if (results.size >= maxDefs) return results
                    }
                }
            }
        }

        return results
    }

    override fun findReferences(
        path: String?,
        line: Int?,
        column: Int?,
        symbolId: String?,
        includeDeclaration: Boolean,
        limit: Int,
        offset: Int,
        revision: SourceRevision
    ): List<ReferenceLocation> {
        val snap = snapshots[revision.toSnapshotId().toKey()] ?: return emptyList()
        val effectiveLimit = limit.coerceIn(1, CodeIntelLimits.MAX_REFERENCES)
        val results = mutableListOf<ReferenceLocation>()

        var targetName: String? = null
        if (path != null && line != null) {
            val file = snap.files[path]
            if (file != null) {
                val symAtPos = file.symbols.firstOrNull {
                    it.range.start.line == line && (column == null || (it.range.start.column <= column && column <= it.range.end.column))
                }
                if (symAtPos != null) {
                    targetName = symAtPos.name
                } else {
                    val refAtPos = file.references.firstOrNull {
                        it.range.start.line == line && (column == null || (it.range.start.column <= column && column <= it.range.end.column))
                    }
                    if (refAtPos != null) {
                        targetName = refAtPos.name
                    }
                }
            }
        }

        if (targetName == null && symbolId != null) {
            for (file in snap.files.values) {
                val sym = file.symbols.firstOrNull { "sym_" + it.qualifiedName.hashCode() == symbolId }
                if (sym != null) {
                    targetName = sym.name
                    break
                }
            }
        }

        if (targetName == null) return emptyList()

        var skipped = 0
        for ((filePath, parseResult) in snap.files) {
            if (includeDeclaration) {
                for (sym in parseResult.symbols) {
                    if (sym.name == targetName) {
                        if (skipped < offset) {
                            skipped++
                            continue
                        }
                        results.add(ReferenceLocation(
                            path = filePath,
                            range = sym.range,
                            symbol = sym.name,
                            refKind = "declaration",
                            confidence = sym.confidence
                        ))
                        if (results.size >= effectiveLimit) return results
                    }
                }
            }
            for (ref in parseResult.references) {
                if (ref.name == targetName) {
                    if (skipped < offset) {
                        skipped++
                        continue
                    }
                    results.add(ReferenceLocation(
                        path = filePath,
                        range = ref.range,
                        symbol = ref.name,
                        refKind = "reference",
                        confidence = ref.confidence
                    ))
                    if (results.size >= effectiveLimit) return results
                }
            }
        }
        return results
    }

    override fun semanticSearch(
        query: String,
        limit: Int,
        revision: SourceRevision
    ): List<SemanticSearchResult> {
        val snap = snapshots[revision.toSnapshotId().toKey()] ?: return emptyList()
        val terms = query.lowercase().split(Regex("""[^a-zA-Z0-9_]+""")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        val effectiveLimit = limit.coerceIn(1, CodeIntelLimits.MAX_SEMANTIC_RESULTS + 1)
        val scored = mutableListOf<SemanticSearchResult>()

        for ((filePath, parseResult) in snap.files) {
            for (sym in parseResult.symbols) {
                var score = 0.0
                val reasons = mutableListOf<String>()
                val nameLower = sym.name.lowercase()
                val qnameLower = sym.qualifiedName.lowercase()

                for (t in terms) {
                    if (nameLower == t) {
                        score += 1.0
                        reasons.add("exact-name")
                    } else if (nameLower.startsWith(t)) {
                        score += 0.6
                        reasons.add("prefix-name")
                    } else if (nameLower.contains(t) || qnameLower.contains(t)) {
                        score += 0.4
                        reasons.add("token-match")
                    }
                }

                if (score > 0.0) {
                    scored.add(SemanticSearchResult(
                        path = filePath,
                        range = sym.range,
                        symbol = sym.name,
                        kind = sym.kind,
                        score = minOf(1.0, score),
                        reasons = reasons.distinct(),
                        snippet = sym.signature
                    ))
                }
            }
        }

        return scored.sortedByDescending { it.score }.take(effectiveLimit)
    }

    override fun dependencyGraph(revision: SourceRevision): DependencyGraph {
        val snap = snapshots[revision.toSnapshotId().toKey()] ?: return DependencyGraph(emptyList(), emptyList())
        val nodes = mutableListOf<DependencyNode>()
        val edges = mutableListOf<DependencyEdge>()

        val rootModId = "mod:" + revision.projectId
        nodes.add(DependencyNode(id = rootModId, kind = DependencyNodeKind.MODULE, label = revision.projectId))

        val seenNodes = mutableSetOf(rootModId)

        for ((path, file) in snap.files) {
            val fileNodeId = "file:" + path
            if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seenNodes.add(fileNodeId)) {
                nodes.add(DependencyNode(id = fileNodeId, kind = DependencyNodeKind.FILE, label = path, path = path))
                if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
                    edges.add(DependencyEdge(sourceId = rootModId, targetId = fileNodeId, kind = DependencyEdgeKind.DECLARES))
                }
            }

            for (imp in file.imports) {
                val targetId = if (imp.importedName != null) "pkg:" + imp.moduleName + "." + imp.importedName else "pkg:" + imp.moduleName
                if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seenNodes.add(targetId)) {
                    nodes.add(DependencyNode(id = targetId, kind = DependencyNodeKind.PACKAGE, label = imp.moduleName))
                }
                if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
                    edges.add(DependencyEdge(sourceId = fileNodeId, targetId = targetId, kind = DependencyEdgeKind.IMPORTS))
                }
            }
        }

        val truncated = nodes.size >= CodeIntelLimits.MAX_GRAPH_NODES || edges.size >= CodeIntelLimits.MAX_GRAPH_EDGES
        return DependencyGraph(nodes, edges, truncated = truncated)
    }

    override fun markDirty(projectId: String) {
        dirtyProjects.add(projectId)
    }

    override fun invalidateAll(projectId: String) {
        dirtyProjects.add(projectId)
        snapshots.keys.filter { it.startsWith(projectId + "@") }.forEach { snapshots.remove(it) }
    }
}

