package dev.wind.agent

object WebAuthorizedFetchTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val seen = ArrayList<String>()
        val denied = Web.fetchAuthorized("https://example.com/page", CancellationToken()) { target ->
            seen += target
            false
        }
        check(denied.contains("FETCH_HOP_NOT_AUTHORIZED")) { denied }
        check(seen == listOf("https://example.com/page")) { seen.toString() }

        val insecure = Web.fetchAuthorized("http://example.com/page", CancellationToken()) {
            error("HTTP must fail before authorization")
        }
        check(insecure.contains(Fa.NET_BLOCK_SCHEME) || insecure.contains("https") || insecure.contains("NET_BLOCK_SCHEME")) { insecure }
        println("WebAuthorizedFetchTests: OK")
    }
}
