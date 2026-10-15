package dev.wind.agent.secrets

import dev.wind.agent.CancellationToken
import dev.wind.agent.artifact.*
import java.io.File
import java.io.FilterInputStream
import java.net.URI
import java.util.Base64

/** Exact HTTPS resource binding; no authenticated redirect forwarding or runtime credential files. */
object SecretDownloads {
    fun downloader(store:VerifiedArtifactStore,quarantine:File,broker:SecretBroker,id:String,url:String,
                   authorize:(URI)->Boolean):SecureArtifactDownloader {
        val resource=SecretDestination.repository(url)
        fun guard(uri:URI,token:CancellationToken) {
            token.throwIfCancelled()
            check(uri.toString()==resource && authorize(uri)) { "SECRET_DOWNLOAD_SCOPE_DENIED" }
            broker.validate(id,resource,"artifact_read")
        }
        val transport=ArtifactHttpTransport { uri,token ->
            guard(uri,token)
            val reply=broker.use(id,resource,"artifact_read") { username,password ->
                val value=Base64.getEncoder().encodeToString((username+":"+String(password)).toByteArray(Charsets.UTF_8))
                PinnedHttpsArtifactTransport(requestHeaders=mapOf("Authorization" to "Basic $value"),quarantineOnly=true,
                    brokerAuthorization=true).get(uri,token)
            }
            ArtifactHttpResponse(reply.status,reply.contentLength,reply.location,object:FilterInputStream(reply.body) {
                override fun read(b:ByteArray,off:Int,len:Int):Int { guard(uri,token);return super.read(b,off,len) }
                override fun read():Int { guard(uri,token);return super.read() }
            },reply.contentType) { reply.close() }
        }
        return SecureArtifactDownloader(store,quarantine,transport,authorizeHop=authorize)
    }
}
