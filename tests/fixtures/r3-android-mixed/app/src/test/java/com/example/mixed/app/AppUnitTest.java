package com.example.mixed.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUnitTest {
    @Test
    public void testEndToEndCrossLanguageFlow() {
        AppJavaCoordinator coordinator = new AppJavaCoordinator();
        AppKotlinWorkflow workflow = new AppKotlinWorkflow();

        String report = workflow.buildFullReport(coordinator, "TestRun");
        assertNotNull(report);
        assertTrue(report.contains("app-kt-wf:[lib-kt:[TestRun]]"));
        assertTrue(report.contains("java-helper:status=TERMINAL_LEAF_TOKEN"));
        assertTrue(report.contains("leaf=COORDINATOR_TERMINAL_LEAF"));
    }
}
