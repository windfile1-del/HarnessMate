package dev.wind.agent.coding

/** Conservative document allowance; JSON, TOML and unknown files still need checks. */
object DocumentationPolicy {
    fun isDocumentation(path: String): Boolean {
        val clean = path.replace('\\', '/').lowercase()
        val segments = clean.split('/')
        if (segments.any { it.startsWith('.') || it in setOf("src", "scripts", "tests", "test", "config", "gradle") }) return false
        val name = segments.last()
        if (name in setOf("requirements.txt", "constraints.txt", "cmakelists.txt")) return false
        return (name.endsWith(".md") || name.endsWith(".txt")) &&
            (segments.size == 1 || segments.first() in setOf("docs", "doc", "reports"))
    }
}
