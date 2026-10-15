package dev.wind.fixture.r2;

import java.util.Objects;

/**
 * Java service participating in Java + Kotlin bidirectional compilation.
 * Avoids runtime recursion by ensuring call flows are strictly acyclic.
 */
public class JavaGreetingService {
    private final String environment;

    public JavaGreetingService(String environment) {
        this.environment = (environment != null && !environment.trim().isEmpty())
                ? environment.trim()
                : "production";
    }

    public String getEnvironment() {
        return environment;
    }

    /**
     * Helper invoked by KotlinGreetingFormatter to format an envelope banner.
     * Pure formatting; does NOT call back into greeting formatting.
     */
    public String formatEnvelope(String header, String payload) {
        Objects.requireNonNull(header, "header must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        return "[" + environment + "] " + header + " -> " + payload;
    }

    /**
     * Java entry point delegating to KotlinGreetingFormatter.
     */
    public String createGreeting(String callerName) {
        KotlinGreetingFormatter formatter = new KotlinGreetingFormatter(this);
        return formatter.buildMessage(callerName);
    }

    /**
     * Java inspects and validates a Kotlin-instantiated FixturePayload.
     */
    public boolean validatePayload(FixturePayload payload) {
        return payload != null && payload.getExecutionCode() >= 0 && !payload.isFailed();
    }
}
