package dev.wind.fixture.r2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin JUnit 4 test verifying Kotlin calling Java and validating failure edge cases.
 */
class KotlinMutualReferenceTest {

    @Test
    fun testKotlinConstructsJavaAndFormatsGreeting() {
        val service = JavaGreetingService("staging")
        val formatter = KotlinGreetingFormatter(service)
        val result = formatter.buildMessage("AndroidARM64")

        assertEquals("[staging] HELLO -> AndroidARM64", result)
        assertEquals("staging", service.environment)
    }

    @Test
    fun testFallbackForBlankSubject() {
        val service = JavaGreetingService("production")
        val greeting = service.createGreeting("   ")

        assertEquals("[production] HELLO -> anonymous", greeting)
    }

    @Test
    fun testPayloadRejectionOnNegativeCode() {
        val service = JavaGreetingService("staging")
        val formatter = KotlinGreetingFormatter(service)

        val exception = assertThrows(IllegalStateException::class.java) {
            formatter.createVerifiedPayload("error-task", -1)
        }
        assertTrue(exception.message!!.contains("rejected payload"))
    }
}
