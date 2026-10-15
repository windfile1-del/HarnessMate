package dev.wind.agent.build

import dev.wind.agent.artifact.VerifiedArtifactStore
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Exact offline POM pin. Repository identity is audit data, not trust authority. */
data class MavenPomPin(
    val coordinate: MavenCoordinate,
    val repositoryId: String,
    val artifactUri: String,
    val sha256: String,
    val byteSize: Int
) {
    init {
        require(repositoryId.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) {
            "MAVEN_REPOSITORY_ID_INVALID"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}")) &&
            artifactUri == "artifact://sha256/$sha256") { "MAVEN_POM_PIN_INVALID" }
        require(byteSize in 1..4 * 1024 * 1024) { "MAVEN_POM_SIZE_INVALID" }
    }
}

/** Reads each POM from the CAS again; a pin cannot bless stale or mutated bytes. */
class CasMavenPomSource(
    private val store: VerifiedArtifactStore,
    pins: List<MavenPomPin>
) : VerifiedMavenPomSource {
    private val byCoordinate: Map<MavenCoordinate, MavenPomPin>

    init {
        require(pins.size <= 2048 && pins.map { it.coordinate }.toSet().size == pins.size) {
            "MAVEN_POM_PINS_INVALID"
        }
        byCoordinate = pins.associateBy { it.coordinate }
    }

    override fun get(coordinate: MavenCoordinate): VerifiedMavenPom? {
        val pin = byCoordinate[coordinate] ?: return null
        val bytes = store.openVerified(pin.artifactUri).use { input ->
            val bytes = ByteArray(pin.byteSize + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            require(count == pin.byteSize) { "MAVEN_POM_SIZE_MISMATCH" }
            bytes.copyOf(count)
        }
        val xml = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return VerifiedMavenPom(coordinate, pin.sha256, xml)
    }
}
