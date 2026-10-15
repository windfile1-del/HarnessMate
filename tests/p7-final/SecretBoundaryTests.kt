package dev.wind.agent

import dev.wind.agent.artifact.PinnedHttpsArtifactTransport
import dev.wind.agent.secrets.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SecretBoundaryTests {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.single()).canonicalFile.apply { mkdirs() }
        val broker=SecretBroker(File(root,"broker"),HostAesGcmSecretCipher())
        val url="https://git.example.org/repo.git"
        val id=broker.save("fixture",url,"fixture-user","fixture-only-token".toCharArray(),setOf("git_read"),System.currentTimeMillis()+3600000,false)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val failure=AtomicReference<Throwable?>()
        val worker=Thread {
            try { broker.use(id,url,"git_read") { _,_ -> entered.countDown();check(release.await(5,TimeUnit.SECONDS)) } }
            catch(e:Throwable) { failure.set(e) }
        }.apply { start() }
        check(entered.await(3,TimeUnit.SECONDS))
        val revoked=CountDownLatch(1)
        Thread { broker.revoke(id);revoked.countDown() }.start()
        try { check(revoked.await(2,TimeUnit.SECONDS)) { "Revoke blocked behind network callback" } }
        finally { release.countDown();worker.join(4000) }
        check(failure.get()==null)
        check(runCatching { broker.validate(id,url,"git_read") }.exceptionOrNull()?.message=="SECRET_NOT_FOUND")
        check(runCatching { PinnedHttpsArtifactTransport(mapOf("Authorization" to "Basic ZmFrZTpmYWtl")) }.isFailure)
        PinnedHttpsArtifactTransport(mapOf("Authorization" to "Basic ZmFrZTpmYWtl"),brokerAuthorization=true)
        check(runCatching { SecretDestination.repository("https://git.example.org/repo.git%2fother") }.isFailure)
        check(!SecretDestination.permits(url,java.net.URI("https://else.example.org/repo.git/info/refs?service=git-upload-pack")))
        println("SecretBoundaryTests PASS (8 checks); revoke stays responsive, header/path boundaries")
    }
}
