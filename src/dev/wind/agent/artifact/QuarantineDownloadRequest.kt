package dev.wind.agent.artifact

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** General downloads are inert private bytes, not automatically usable tool inputs. */
internal object QuarantineDownloadPolicy {
    fun parseUrl(raw: String): URI {
        if (raw.length !in 1..8192 || raw.any { it <= ' ' || it == '\u007f' }) {
            throw ArtifactDownloadException("DOWNLOAD_URL_INVALID")
        }
        val uri = try { URI(URI(raw).toASCIIString()) } catch (_: Exception) {
            throw ArtifactDownloadException("DOWNLOAD_URL_INVALID")
        }
        if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https") ||
            uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
            uri.port !in -1..65535 || uri.port == 0) {
            throw ArtifactDownloadException("DOWNLOAD_DESTINATION_BLOCKED")
        }
        return uri
    }
}

/** A source snapshot; not a git clone and does not include .git, LFS or submodules. */
internal data class GithubRepositoryDownload(val repositoryUrl: String, val ref: String, val downloadUrl: String) {
    companion object {
        fun parse(repositoryUrl: String, ref: String = "HEAD"): GithubRepositoryDownload {
            val uri = QuarantineDownloadPolicy.parseUrl(repositoryUrl)
            if (!uri.scheme.equals("https", true) || !uri.host.equals("github.com", true) ||
                uri.port !in listOf(-1, 443) || uri.rawQuery != null) {
                throw ArtifactDownloadException("GITHUB_REPOSITORY_URL_INVALID")
            }
            val segments = uri.path.trim('/').split('/')
            if (segments.size != 2) throw ArtifactDownloadException("GITHUB_REPOSITORY_URL_INVALID")
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            val component = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
            if (!component.matches(owner) || !component.matches(repo)) {
                throw ArtifactDownloadException("GITHUB_REPOSITORY_URL_INVALID")
            }
            if (ref.isBlank() || ref.length > 256 || ref.any { it <= ' ' || it == '\u007f' } ||
                ref.startsWith('/') || ref.endsWith('/') || ref.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                throw ArtifactDownloadException("GITHUB_REF_INVALID")
            }
            val encodedRef = URLEncoder.encode(ref, "UTF-8").replace("+", "%20")
            return GithubRepositoryDownload("https://github.com/$owner/$repo", ref,
                "https://codeload.github.com/$owner/$repo/zip/$encodedRef")
        }
    }
}
