package dev.wind.fixture.r2;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JUnit 4 test verifying Java calling Kotlin which references Java,
 * validating both joint compilation and runtime acyclic execution.
 */
public class JavaMutualReferenceTest {

    @Test
    public void testJavaGreetingDelegationToKotlin() {
        JavaGreetingService service = new JavaGreetingService("test-env");
        String greeting = service.createGreeting("Vega");
        assertEquals("[test-env] HELLO -> Vega", greeting);
    }

    @Test
    public void testJavaValidatesKotlinPayload() {
        JavaGreetingService service = new JavaGreetingService("ci");
        KotlinGreetingFormatter formatter = new KotlinGreetingFormatter(service);
        FixturePayload payload = formatter.createVerifiedPayload("task-42", 0);

        assertNotNull(payload);
        assertEquals("task-42", payload.getId());
        assertEquals(0, payload.getExecutionCode());
        assertTrue(service.validatePayload(payload));
    }

    @Test
    public void testDirectEnvelopeFormatting() {
        JavaGreetingService service = new JavaGreetingService("dev");
        KotlinGreetingFormatter formatter = new KotlinGreetingFormatter(service);
        String direct = formatter.createEnvelopeDirect("ping", "pong");
        assertEquals("[dev] PING -> pong", direct);
    }
}
