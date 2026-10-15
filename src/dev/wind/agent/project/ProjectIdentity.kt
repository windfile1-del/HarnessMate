package dev.wind.agent.project

import java.security.MessageDigest

object ProjectIdentity {
    fun remoteId(projectId: String): String {
        require(projectId.isNotBlank()) { "Project ID cannot be blank" }
        return MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun remoteRoot(projectId: String): String {
        val id = remoteId(projectId)
        return "/data/data/com.termux/files/home/.vega-safe/projects/$id"
    }
}
