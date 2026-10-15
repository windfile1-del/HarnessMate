package com.example.mixed.library

import androidx.annotation.NonNull

class LibraryKotlinService {
    @NonNull
    fun formatMessage(@NonNull rawInput: String): String {
        val helper = LibraryJavaHelper()
        val cleaned = helper.sanitize(rawInput)
        return "lib-kt:[$cleaned]"
    }

    @NonNull
    fun getTerminalToken(): String {
        return "TERMINAL_LEAF_TOKEN"
    }
}
