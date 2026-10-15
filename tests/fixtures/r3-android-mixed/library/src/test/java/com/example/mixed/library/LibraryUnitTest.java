package com.example.mixed.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class LibraryUnitTest {
    @Test
    public void testLibraryCrossReferenceWithTerminal() {
        LibraryKotlinService service = new LibraryKotlinService();
        LibraryJavaHelper helper = new LibraryJavaHelper();

        String formatted = service.formatMessage("  hello world  ");
        assertEquals("lib-kt:[hello world]", formatted);

        String status = helper.assembleStatus(service);
        assertEquals("java-helper:status=TERMINAL_LEAF_TOKEN", status);
        assertNotNull(service.getTerminalToken());
    }
}
