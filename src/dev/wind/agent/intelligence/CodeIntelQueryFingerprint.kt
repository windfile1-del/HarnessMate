package dev.wind.agent.intelligence

import java.security.MessageDigest

/** Canonical, argument-sensitive cursor binding. */
object CodeIntelQueryFingerprint {
    fun of(vararg parts: Any?): String {
        val canonical = parts.joinToString("\u001f") { canonicalPart(it) }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(32)
    }

    private fun canonicalPart(value: Any?): String = when (value) {
        null -> "<null>"
        is Set<*> -> value.map { canonicalPart(it) }.sorted().joinToString(",", "[", "]")
        is Iterable<*> -> value.joinToString(",", "[", "]") { canonicalPart(it) }
        else -> value.toString()
    }
}

