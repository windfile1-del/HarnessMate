package dev.wind.fixture.r2

/**
 * Kotlin data class representing a structured fixture payload consumed by Java.
 */
data class FixturePayload(
    val id: String,
    val executionCode: Int,
    val isFailed: Boolean
)

/**
 * Kotlin formatter participating in Java + Kotlin bidirectional compilation.
 * References JavaGreetingService directly while avoiding runtime recursive calls.
 */
class KotlinGreetingFormatter(private val service: JavaGreetingService) {

    /**
     * Builds greeting message by querying Java service envelope method.
     */
    fun buildMessage(subject: String?): String {
        val sanitized = subject?.trim()?.ifEmpty { "anonymous" } ?: "anonymous"
        val header = "HELLO"
        return service.formatEnvelope(header, sanitized)
    }

    /**
     * Creates and validates a fixture payload using Java service validation.
     */
    fun createVerifiedPayload(id: String, code: Int): FixturePayload {
        val payload = FixturePayload(id = id, executionCode = code, isFailed = code < 0)
        val valid = service.validatePayload(payload)
        check(valid) { "Java service rejected payload with code $code" }
        return payload
    }

    /**
     * Direct envelope generation for specific diagnostic tag.
     */
    fun createEnvelopeDirect(tag: String, value: String): String {
        return service.formatEnvelope(tag.trim().uppercase(), value.trim())
    }
}
