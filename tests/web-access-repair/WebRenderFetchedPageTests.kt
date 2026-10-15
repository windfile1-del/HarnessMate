package dev.wind.agent.testing

import dev.wind.agent.CancellationToken
import dev.wind.agent.Web
import dev.wind.agent.WebHttpTransport

object WebRenderFetchedPageTests {

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting WebRenderFetchedPageTests...")
        testHtmlRenderingTitleAndLinks()
        testMissingTypeHtmlSniffing()
        testPlainTextPreservesGenericsAndMarkdown()
        testPlainTextCaptchaMentionNotBlocked()
        testJsonAndXmlPreservedRaw()
        testHtmlAntiBotPageBlocked()
        println("WebRenderFetchedPageTests: ALL PASS")
    }

    private fun testHtmlRenderingTitleAndLinks() {
        val html = """
        <!DOCTYPE html>
        <html>
        <head><title>Test Page Title</title></head>
        <body>
            <h1>Header</h1>
            <p>Main body content paragraph.</p>
            <a href="https://example.com/subpage">Subpage Link</a>
        </body>
        </html>
        """.trimIndent()

        val reply = WebHttpTransport.Reply(
            finalUrl = "https://example.com/page",
            text = html,
            contentType = "text/html; charset=utf-8",
            sha256 = "dummy-sha",
            byteSize = html.length
        )

        val rendered = Web.renderFetchedPage(reply, CancellationToken())
        check(rendered.contains("Title: Test Page Title")) { "Title missing in rendered output: $rendered" }
        check(rendered.contains("URL: https://example.com/page"))
        check(rendered.contains("Main body content paragraph."))
        check(rendered.contains("https://example.com/subpage")) { "Link missing: $rendered" }
        println("  testHtmlRenderingTitleAndLinks: PASS")
    }

    private fun testMissingTypeHtmlSniffing() {
        val html = "<html><head><title>Sniffed Title</title></head><body><p>Sniffed HTML body.</p></body></html>"
        val reply = WebHttpTransport.Reply(
            finalUrl = "https://example.com/sniffed",
            text = html,
            contentType = "", // Empty content type
            sha256 = "dummy-sha",
            byteSize = html.length
        )

        val rendered = Web.renderFetchedPage(reply, CancellationToken())
        check(rendered.contains("Title: Sniffed Title")) { "Title should be detected via sniffing: $rendered" }
        check(rendered.contains("Sniffed HTML body."))
        println("  testMissingTypeHtmlSniffing: PASS")
    }

    private fun testPlainTextPreservesGenericsAndMarkdown() {
        val plainText = """
        # Kotlin Generics Documentation
        Here is a function signature:
        fun <T : Comparable<T>> sort(list: List<T>): List<T>
        And markdown elements: *emphasis*, **bold**, code elements
        """.trimIndent()

        val reply = WebHttpTransport.Reply(
            finalUrl = "https://example.com/docs.txt",
            text = plainText,
            contentType = "text/plain",
            sha256 = "dummy-sha",
            byteSize = plainText.length
        )

        val rendered = Web.renderFetchedPage(reply, CancellationToken())
        check(rendered.contains("fun <T : Comparable<T>> sort(list: List<T>): List<T>")) {
            "Code generics were stripped or corrupted: $rendered"
        }
        check(rendered.contains("# Kotlin Generics Documentation"))
        check(!rendered.contains("Title:")) { "Plain text should not have Title header: $rendered" }
        println("  testPlainTextPreservesGenericsAndMarkdown: PASS")
    }

    private fun testPlainTextCaptchaMentionNotBlocked() {
        val plainText = """
        Security Overview:
        This service protects endpoints using a cloudflare challenge or hcaptcha verification page.
        If a bot is detected, please solve the captcha to proceed.
        """.trimIndent()

        val reply = WebHttpTransport.Reply(
            finalUrl = "https://example.com/rfc-security.txt",
            text = plainText,
            contentType = "text/plain",
            sha256 = "dummy-sha",
            byteSize = plainText.length
        )

        val rendered = Web.renderFetchedPage(reply, CancellationToken())
        check(!rendered.contains("ERROR: the site returned an anti-bot or access-check page")) {
            "Plain text mentioning captcha was false-blocked: $rendered"
        }
        check(rendered.contains("This service protects endpoints using a cloudflare challenge"))
        println("  testPlainTextCaptchaMentionNotBlocked: PASS")
    }

    private fun testJsonAndXmlPreservedRaw() {
        val json = """{"status": "ok", "items": ["<generic>", "<T>"]}"""
        val jsonReply = WebHttpTransport.Reply(
            finalUrl = "https://example.com/api.json",
            text = json,
            contentType = "application/json",
            sha256 = "dummy-sha",
            byteSize = json.length
        )
        val jsonRendered = Web.renderFetchedPage(jsonReply, CancellationToken())
        check(jsonRendered.contains(json)) { "JSON was modified: $jsonRendered" }

        val xml = """<config><node value="test"><item id="1"/></node></config>"""
        val xmlReply = WebHttpTransport.Reply(
            finalUrl = "https://example.com/feed.xml",
            text = xml,
            contentType = "application/xml",
            sha256 = "dummy-sha",
            byteSize = xml.length
        )
        val xmlRendered = Web.renderFetchedPage(xmlReply, CancellationToken())
        check(xmlRendered.contains(xml)) { "XML was modified: $xmlRendered" }
        println("  testJsonAndXmlPreservedRaw: PASS")
    }

    private fun testHtmlAntiBotPageBlocked() {
        val antiBotHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>Just a moment...</title></head>
        <body>
            <div id="challenge-running">Please wait while we verify you are human... Cloudflare Ray ID: 12345</div>
            <div>Attention Required! | Cloudflare</div>
        </body>
        </html>
        """.trimIndent()

        val reply = WebHttpTransport.Reply(
            finalUrl = "https://example.com/blocked",
            text = antiBotHtml,
            contentType = "text/html",
            sha256 = "dummy-sha",
            byteSize = antiBotHtml.length
        )

        val rendered = Web.renderFetchedPage(reply, CancellationToken())
        check(rendered.contains("ERROR: the site returned an anti-bot or access-check page")) {
            "Anti-bot HTML should be blocked, got: $rendered"
        }
        println("  testHtmlAntiBotPageBlocked: PASS")
    }
}
