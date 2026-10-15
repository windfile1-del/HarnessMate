package dev.wind.agent.intelligence.index

import dev.wind.agent.CancellationToken
import dev.wind.agent.intelligence.*

data class IndexManifestFile(
    val path: String,
    val size: Long,
    val sha256: String?
)

interface CodeIndexBackend {
    fun status(revision: SourceRevision): CodeIndexStatus

    fun buildCandidate(
        candidateId: String,
        revision: SourceRevision,
        fileReader: (String) -> ByteArray?,
        manifestFiles: List<String>,
        token: CancellationToken = CancellationToken()
    ): Boolean

    /** Candidate build with the exact P3 manifest metadata observed at start. */
    fun buildCandidateWithManifest(
        candidateId: String,
        revision: SourceRevision,
        fileReader: (String) -> ByteArray?,
        manifestFiles: List<IndexManifestFile>,
        token: CancellationToken = CancellationToken()
    ): Boolean = buildCandidate(candidateId, revision, fileReader, manifestFiles.map { it.path }, token)

    fun commitCandidate(
        candidateId: String,
        revisionBefore: SourceRevision,
        revisionAfter: SourceRevision
    ): Boolean

    fun discardCandidate(candidateId: String)

    fun ensureFresh(
        revision: SourceRevision,
        fileReader: (String) -> ByteArray?,
        manifestFiles: List<String>,
        token: CancellationToken = CancellationToken()
    ): Boolean

    fun searchSymbols(
        query: String,
        kinds: Set<SymbolKind>,
        language: CodeLanguage?,
        pathPrefix: String?,
        limit: Int,
        offset: Int,
        revision: SourceRevision
    ): List<SymbolLocation>

    fun findDefinitions(
        path: String?,
        line: Int?,
        column: Int?,
        symbolId: String?,
        revision: SourceRevision
    ): List<SymbolLocation>

    fun findReferences(
        path: String?,
        line: Int?,
        column: Int?,
        symbolId: String?,
        includeDeclaration: Boolean,
        limit: Int,
        offset: Int,
        revision: SourceRevision
    ): List<ReferenceLocation>

    fun semanticSearch(
        query: String,
        limit: Int,
        revision: SourceRevision
    ): List<SemanticSearchResult>

    fun dependencyGraph(
        revision: SourceRevision
    ): DependencyGraph

    fun markDirty(projectId: String)

    fun invalidateAll(projectId: String)
}

