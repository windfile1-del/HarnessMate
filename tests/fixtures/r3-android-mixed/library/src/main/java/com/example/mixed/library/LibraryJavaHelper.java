package com.example.mixed.library;

import androidx.annotation.NonNull;

public class LibraryJavaHelper {
    @NonNull
    public String sanitize(@NonNull String input) {
        return input.trim();
    }

    @NonNull
    public String assembleStatus(@NonNull LibraryKotlinService service) {
        return "java-helper:status=" + service.getTerminalToken();
    }
}
