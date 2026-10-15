package dev.wind.agent.shell.mirror

data class ProjectManifest(
    val projectId: String,
    val generation: Long,
    val topologyHash: String,
    val mountManifests: Map<String, WorkspaceManifest>
)
