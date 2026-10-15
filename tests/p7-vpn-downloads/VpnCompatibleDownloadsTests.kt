package dev.wind.agent.artifact

import dev.wind.agent.CancellationToken
import dev.wind.agent.runtime.linux.RuntimeToolCatalog
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object VpnCompatibleDownloadsTests {
    private var checksPassed = 0

    private fun checkAssert(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
        checksPassed++
    }

    private fun assertThrows(expectedSubstring: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected exception containing '$expectedSubstring', but none was thrown")
        } catch (e: AssertionError) {
            throw e
        } catch (t: Throwable) {
            val msg = t.message ?: ""
            if (!msg.contains(expectedSubstring)) {
                throw AssertionError("Expected exception message containing '$expectedSubstring', but got '${t.javaClass.name}: $msg'", t)
            }
            checksPassed++
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        checksPassed = 0
        val base = args.firstOrNull()?.let(Path::of)
            ?: System.getProperty("vega.test.temp")?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-vpn-test-").toFile()
        try {
            testDefaultStrictTransport()
            testApprovedHostsVpnUnicastAllowed()
            testApprovedHostsStrictlyRejectLoopbackAndInvalid()
            testUnapprovedHostsAndDestinationAttacksRejected()
            testTlsEndpointIdentificationAlgorithm()
            testRuntimeToolPacksFixtureTransport(root)
            println("VpnCompatibleDownloadsTests: OK ($checksPassed assertions passed)")
        } finally {
            root.deleteRecursively()
        }
    }

    /** 1. Default strict transport still rejects 198.18/198.19, ULA, RFC1918, and loopback. */
    private fun testDefaultStrictTransport() {
        val defaultTransport = PinnedHttpsArtifactTransport()

        val fakeIpv4 = InetAddress.getByName("198.18.0.25")
        val fakeIpv4b = InetAddress.getByName("198.19.100.1")
        val rfc1918a = InetAddress.getByName("10.0.0.1")
        val rfc1918b = InetAddress.getByName("172.16.0.1")
        val rfc1918c = InetAddress.getByName("192.168.1.1")
        val ipv6Ula = InetAddress.getByName("fd00::1")
        val ipv6Ulab = InetAddress.getByName("fc00:1::1")
        val loopback4 = InetAddress.getByName("127.0.0.1")
        val loopback6 = InetAddress.getByName("::1")
        val publicIp = InetAddress.getByName("140.82.121.4")

        // Default strict transport rejects all non-public IPs even if host is github.com
        checkAssert(!defaultTransport.addressAllowed(fakeIpv4, "github.com"), "Default must reject 198.18")
        checkAssert(!defaultTransport.addressAllowed(fakeIpv4b, "github.com"), "Default must reject 198.19")
        checkAssert(!defaultTransport.addressAllowed(rfc1918a, "github.com"), "Default must reject 10.x")
        checkAssert(!defaultTransport.addressAllowed(rfc1918b, "github.com"), "Default must reject 172.16.x")
        checkAssert(!defaultTransport.addressAllowed(rfc1918c, "github.com"), "Default must reject 192.168.x")
        checkAssert(!defaultTransport.addressAllowed(ipv6Ula, "github.com"), "Default must reject ULA fd00::")
        checkAssert(!defaultTransport.addressAllowed(ipv6Ulab, "github.com"), "Default must reject ULA fc00::")
        checkAssert(!defaultTransport.addressAllowed(loopback4, "github.com"), "Default must reject 127.0.0.1")
        checkAssert(!defaultTransport.addressAllowed(loopback6, "github.com"), "Default must reject ::1")
        checkAssert(!defaultTransport.addressAllowed(fakeIpv4, null), "Default must reject without host")
        checkAssert(defaultTransport.addressAllowed(publicIp, "github.com"), "Default allows public IP")
    }

    /** 2. Approved github.com + release-assets + objects allow FakeIPv4, IPv6 ULA, RFC1918, and public. */
    private fun testApprovedHostsVpnUnicastAllowed() {
        val approvedHosts = RuntimeToolCatalog.networkDomains
        checkAssert(approvedHosts.contains("github.com"), "approvedHosts contains github.com")
        checkAssert(approvedHosts.contains("release-assets.githubusercontent.com"), "approvedHosts contains release-assets")
        checkAssert(approvedHosts.contains("objects.githubusercontent.com"), "approvedHosts contains objects")

        val vpnTransport = PinnedHttpsArtifactTransport(vpnCompatibleHttpsHosts = approvedHosts)

        val fakeIpv4 = InetAddress.getByName("198.18.42.1")
        val fakeIpv4b = InetAddress.getByName("198.19.1.99")
        val rfc1918a = InetAddress.getByName("10.10.10.10")
        val rfc1918b = InetAddress.getByName("172.20.0.5")
        val rfc1918c = InetAddress.getByName("192.168.50.1")
        val ipv6Ula = InetAddress.getByName("fd12:3456:789a::1")
        val ipv6Ulab = InetAddress.getByName("fc00::cafe")
        val publicIp = InetAddress.getByName("140.82.121.4")
        val publicIpv6 = InetAddress.getByName("2606:4700:4700::1111")

        for (host in approvedHosts) {
            checkAssert(vpnTransport.addressAllowed(fakeIpv4, host), "Approved host $host allows FakeIPv4 198.18")
            checkAssert(vpnTransport.addressAllowed(fakeIpv4b, host), "Approved host $host allows FakeIPv4 198.19")
            checkAssert(vpnTransport.addressAllowed(rfc1918a, host), "Approved host $host allows 10.x")
            checkAssert(vpnTransport.addressAllowed(rfc1918b, host), "Approved host $host allows 172.x")
            checkAssert(vpnTransport.addressAllowed(rfc1918c, host), "Approved host $host allows 192.168.x")
            checkAssert(vpnTransport.addressAllowed(ipv6Ula, host), "Approved host $host allows ULA fd12::")
            checkAssert(vpnTransport.addressAllowed(ipv6Ulab, host), "Approved host $host allows ULA fc00::")
            checkAssert(vpnTransport.addressAllowed(publicIp, host), "Approved host $host allows public IPv4")
            checkAssert(vpnTransport.addressAllowed(publicIpv6, host), "Approved host $host allows public IPv6")
        }
    }

    /** 3. Approved hosts still strictly reject loopback, link-local, any-local, multicast, and 0/224+. */
    private fun testApprovedHostsStrictlyRejectLoopbackAndInvalid() {
        val approvedHosts = RuntimeToolCatalog.networkDomains
        val vpnTransport = PinnedHttpsArtifactTransport(vpnCompatibleHttpsHosts = approvedHosts)

        val loopback4 = InetAddress.getByName("127.0.0.1")
        val loopback6 = InetAddress.getByName("::1")
        val anyLocal4 = InetAddress.getByName("0.0.0.0")
        val anyLocal6 = InetAddress.getByName("::")
        val linkLocal4 = InetAddress.getByName("169.254.1.2")
        val linkLocal6 = InetAddress.getByName("fe80::1")
        val multicast4 = InetAddress.getByName("224.0.0.1")
        val multicast4b = InetAddress.getByName("239.255.255.250")
        val multicast6 = InetAddress.getByName("ff02::1")
        val classE = InetAddress.getByName("240.0.0.1")
        val zeroNetwork = InetAddress.getByName("0.1.2.3")

        for (host in approvedHosts) {
            checkAssert(!vpnTransport.addressAllowed(loopback4, host), "Must reject 127.0.0.1 for $host")
            checkAssert(!vpnTransport.addressAllowed(loopback6, host), "Must reject ::1 for $host")
            checkAssert(!vpnTransport.addressAllowed(anyLocal4, host), "Must reject 0.0.0.0 for $host")
            checkAssert(!vpnTransport.addressAllowed(anyLocal6, host), "Must reject :: for $host")
            checkAssert(!vpnTransport.addressAllowed(linkLocal4, host), "Must reject link-local IPv4 for $host")
            checkAssert(!vpnTransport.addressAllowed(linkLocal6, host), "Must reject link-local IPv6 for $host")
            checkAssert(!vpnTransport.addressAllowed(multicast4, host), "Must reject multicast IPv4 for $host")
            checkAssert(!vpnTransport.addressAllowed(multicast4b, host), "Must reject multicast IPv4 b for $host")
            checkAssert(!vpnTransport.addressAllowed(multicast6, host), "Must reject multicast IPv6 for $host")
            checkAssert(!vpnTransport.addressAllowed(classE, host), "Must reject class E for $host")
            checkAssert(!vpnTransport.addressAllowed(zeroNetwork, host), "Must reject 0.x network for $host")
        }
    }

    /** 4. Unapproved hosts not relaxed + suffix spoof, HTTP downgrade, IP literal, userinfo, port reject. */
    private fun testUnapprovedHostsAndDestinationAttacksRejected() {
        val approvedHosts = RuntimeToolCatalog.networkDomains
        val vpnTransport = PinnedHttpsArtifactTransport(vpnCompatibleHttpsHosts = approvedHosts)
        val fakeIp = InetAddress.getByName("198.18.0.1")

        // Unapproved host gets strict check (not relaxed)
        checkAssert(!vpnTransport.addressAllowed(fakeIp, "fakegithub.com"), "fakegithub.com not relaxed")
        checkAssert(!vpnTransport.addressAllowed(fakeIp, "github.com.evil.com"), "github.com.evil.com not relaxed")
        checkAssert(!vpnTransport.addressAllowed(fakeIp, "api.github.com"), "api.github.com not relaxed")
        checkAssert(!vpnTransport.addressAllowed(fakeIp, "example.com"), "example.com not relaxed")
        checkAssert(!vpnTransport.addressAllowed(fakeIp, null), "null host not relaxed")

        // validateDestination: Suffix spoofing
        assertThrows("DOWNLOAD_HOST_NOT_APPROVED") {
            vpnTransport.validateDestination(URI("https://fakegithub.com/archive.tar.gz"))
        }
        assertThrows("DOWNLOAD_HOST_NOT_APPROVED") {
            vpnTransport.validateDestination(URI("https://github.com.evil.com/archive.tar.gz"))
        }
        assertThrows("DOWNLOAD_HOST_NOT_APPROVED") {
            vpnTransport.validateDestination(URI("https://notobjects.githubusercontent.com/archive.tar.gz"))
        }

        // validateDestination: HTTP downgrade
        assertThrows("DOWNLOAD_DESTINATION_BLOCKED") {
            vpnTransport.validateDestination(URI("http://github.com/archive.tar.gz"))
        }

        // validateDestination: IP literal
        assertThrows("DOWNLOAD_DESTINATION_BLOCKED") {
            vpnTransport.validateDestination(URI("https://198.18.0.1/archive.tar.gz"))
        }

        // validateDestination: localhost and internal
        assertThrows("DOWNLOAD_DESTINATION_BLOCKED") {
            vpnTransport.validateDestination(URI("https://localhost/archive.tar.gz"))
        }
        assertThrows("DOWNLOAD_DESTINATION_BLOCKED") {
            vpnTransport.validateDestination(URI("https://myhost.local/archive.tar.gz"))
        }

        // validateDestination: userinfo
        assertThrows("DOWNLOAD_DESTINATION_BLOCKED") {
            vpnTransport.validateDestination(URI("https://admin:secret@github.com/archive.tar.gz"))
        }

        // validateDestination: port not 443
        assertThrows("DOWNLOAD_DESTINATION_BLOCKED") {
            vpnTransport.validateDestination(URI("https://github.com:8443/archive.tar.gz"))
        }
        assertThrows("DOWNLOAD_DESTINATION_BLOCKED") {
            vpnTransport.validateDestination(URI("https://github.com:80/archive.tar.gz"))
        }

        // QuarantineOnly cannot be combined with vpnCompatibleHttpsHosts
        assertThrows("DOWNLOAD_ROUTE_POLICY_INVALID") {
            PinnedHttpsArtifactTransport(quarantineOnly = true, vpnCompatibleHttpsHosts = approvedHosts)
        }

        // Approved destination passes validateDestination
        val validUri = vpnTransport.validateDestination(URI("https://github.com/owner/repo/releases/tag/v1.0"))
        checkAssert(validUri.host == "github.com", "Approved URI valid")
    }

    /** 5. TLS HTTPS endpoint identification algorithm retained. */
    private fun testTlsEndpointIdentificationAlgorithm() {
        val vpnTransport = PinnedHttpsArtifactTransport(vpnCompatibleHttpsHosts = RuntimeToolCatalog.networkDomains)
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        try {
            val configured = vpnTransport.configureHostnameVerification(socket)
            checkAssert(configured, "configureHostnameVerification returns true")
            checkAssert(socket.sslParameters.endpointIdentificationAlgorithm == "HTTPS", "endpoint identification algorithm must be HTTPS")
        } finally {
            socket.close()
        }
    }

    /** 6. Fixture transport verifying SecureArtifactDownloader.runtimeToolPacks. */
    private fun testRuntimeToolPacksFixtureTransport(root: File) {
        val store = VerifiedArtifactStore(File(root, "store"))
        val quarantine = File(root, "quarantine")

        // Factory requires non-empty approvedHosts
        assertThrows("DOWNLOAD_APPROVED_HOSTS_REQUIRED") {
            SecureArtifactDownloader.runtimeToolPacks(store, quarantine, emptySet()) { true }
        }

        val approved = setOf("github.com", "objects.githubusercontent.com")
        val downloader = SecureArtifactDownloader.runtimeToolPacks(store, quarantine, approved) { true }

        // Unapproved starting host rejected before download/connect
        assertThrows("DOWNLOAD_HOST_NOT_APPROVED") {
            downloader.download("https://evil.com/payload.tar.gz", "unapproved host test", "0".repeat(64), 1024, CancellationToken())
        }

        // HTTP downgrade starting host rejected before download/connect
        assertThrows("DOWNLOAD_DESTINATION_BLOCKED") {
            downloader.download("http://github.com/payload.tar.gz", "http downgrade test", "0".repeat(64), 1024, CancellationToken())
        }

        // Fixture transport testing redirect boundary
        val mockRedirectTransport = object : ArtifactHttpTransport {
            var calls = 0
            override fun get(uri: URI, token: CancellationToken): ArtifactHttpResponse {
                calls++
                return when (uri.toString()) {
                    "https://github.com/repo/releases/download/v1/pack.hm-pack" -> {
                        ArtifactHttpResponse(302, 0L, "https://unapproved-cdn.com/file.bin", ByteArrayInputStream(ByteArray(0))) { }
                    }
                    else -> throw IllegalStateException("Unexpected URI: $uri")
                }
            }
        }

        // Use custom transport with the exact same validator as runtimeToolPacks:
        val transportValidator = PinnedHttpsArtifactTransport(vpnCompatibleHttpsHosts = approved)
        val fixtureDownloader = SecureArtifactDownloader(
            store, quarantine, mockRedirectTransport, { it.usableSpace },
            { transportValidator.validateDestination(URI(it)) }, { true }
        )

        // Redirect to unapproved domain fails at redirect validation
        assertThrows("DOWNLOAD_HOST_NOT_APPROVED") {
            fixtureDownloader.download(
                "https://github.com/repo/releases/download/v1/pack.hm-pack",
                "redirect to unapproved host test",
                "0".repeat(64),
                1024,
                CancellationToken()
            )
        }
        checkAssert(mockRedirectTransport.calls == 1, "Mock transport was called once before redirect rejected")
    }
}

