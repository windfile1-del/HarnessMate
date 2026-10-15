package dev.wind.agent.testing

import dev.wind.agent.capability.*
import dev.wind.agent.project.MountMode
import dev.wind.agent.project.MountState
import dev.wind.agent.project.VegaProject
import dev.wind.agent.project.WorkspaceMount
import org.json.JSONObject

object WebCapabilityRegressionTests {

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting WebCapabilityRegressionTests...")
        testDefaultSearchDomainsResolver()
        testCustomSearchDomainsResolver()
        testEmptySearchDomainsDenied()
        testBrokerApprovedDomainPermitSearchAndHop()
        testBrokerUnrelatedRedirectDenied()
        testBrokerRevocation()
        testBrokerFullTaskNetworkWorks()
        println("WebCapabilityRegressionTests: ALL PASS")
    }

    private fun testDefaultSearchDomainsResolver() {
        val resolver = CapabilityResolver { null }
        val context = TaskContext("task-1", "p1", 1L, CapabilityMode.ACCEPT, "task-1")
        val req = resolver.resolve(context, "web_search", JSONObject())

        check(req is CapabilityRequirement.Network) { "Expected Network requirement, got: $req" }
        check(req.domains == setOf("lite.duckduckgo.com")) {
            "Expected default DDG domain, got: ${req.domains}"
        }
        println("  testDefaultSearchDomainsResolver: PASS")
    }

    private fun testCustomSearchDomainsResolver() {
        val expectedDomains = setOf("api.search.brave.com", "api.tavily.com", "searx.example.com", "lite.duckduckgo.com")
        val resolver = CapabilityResolver(
            projectLookup = { null },
            dependencyPlanLookup = null,
            downloadPlanLookup = null,
            apkPlanLookup = null,
            searchDomains = { expectedDomains }
        )
        val context = TaskContext("task-1", "p1", 1L, CapabilityMode.ACCEPT, "task-1")
        val req = resolver.resolve(context, "web_search", JSONObject())

        check(req is CapabilityRequirement.Network) { "Expected Network requirement, got: $req" }
        check(req.domains == expectedDomains) {
            "Expected concrete search domains, got: ${req.domains}"
        }
        check(req.domains.isNotEmpty()) { "web_search domains must not be empty" }
        println("  testCustomSearchDomainsResolver: PASS")
    }

    private fun testEmptySearchDomainsDenied() {
        val resolver = CapabilityResolver(
            projectLookup = { null },
            dependencyPlanLookup = null,
            downloadPlanLookup = null,
            apkPlanLookup = null,
            searchDomains = { emptySet() }
        )
        val context = TaskContext("task-1", "p1", 1L, CapabilityMode.ACCEPT, "task-1")
        val req = resolver.resolve(context, "web_search", JSONObject())

        check(req is CapabilityRequirement.Denied) { "Expected Denied when no search domains configured" }
        check(req.reason == "SEARCH_PROVIDER_CONFIGURATION_INVALID") { "Unexpected reason: ${req.reason}" }
        println("  testEmptySearchDomainsDenied: PASS")
    }

    private fun testBrokerApprovedDomainPermitSearchAndHop() {
        val store = CapabilityStore.inMemory()
        val broker = CapabilityBrokerImpl(store, { null }, { MountState.AVAILABLE })
        val context = TaskContext("task-1", "p1", 1L, CapabilityMode.ACCEPT, "task-1")

        val tavilyReq = CapabilityRequirement.Network("p1", setOf("api.tavily.com"))

        // Initial check needs approval
        val check1 = broker.check(context, tavilyReq)
        check(check1 is CapabilityCheck.NeedsApproval) { "Expected NeedsApproval initially" }
        val reqId = (check1 as CapabilityCheck.NeedsApproval).request.requestId

        // Grant domain
        val lease = broker.grant(reqId, CapabilityDecision.Allow(GrantScope.TASK))
        checkNotNull(lease) { "Grant failed" }

        // Now tavily domain is granted
        val check2 = broker.check(context, tavilyReq)
        check(check2 is CapabilityCheck.Allowed) { "Expected Allowed for approved search domain" }
        println("  testBrokerApprovedDomainPermitSearchAndHop: PASS")
    }

    private fun testBrokerUnrelatedRedirectDenied() {
        val store = CapabilityStore.inMemory()
        val broker = CapabilityBrokerImpl(store, { null }, { MountState.AVAILABLE })
        val context = TaskContext("task-1", "p1", 1L, CapabilityMode.ACCEPT, "task-1")

        // Approve only api.tavily.com
        val tavilyReq = CapabilityRequirement.Network("p1", setOf("api.tavily.com"))
        val pending = broker.check(context, tavilyReq) as CapabilityCheck.NeedsApproval
        broker.grant(pending.request.requestId, CapabilityDecision.Allow(GrantScope.TASK))

        // Verify approved domain is granted
        check(broker.check(context, tavilyReq) is CapabilityCheck.Allowed)

        // Unrelated redirect target must be DENIED (needs approval)
        val unrelatedHop = CapabilityRequirement.Network("p1", setOf("unrelated-redirect.evil.com"))
        val hopCheck = broker.check(context, unrelatedHop)
        check(hopCheck is CapabilityCheck.NeedsApproval) {
            "Unrelated redirect domain must not be automatically granted, got: $hopCheck"
        }
        println("  testBrokerUnrelatedRedirectDenied: PASS")
    }

    private fun testBrokerRevocation() {
        val store = CapabilityStore.inMemory()
        val broker = CapabilityBrokerImpl(store, { null }, { MountState.AVAILABLE })
        val context = TaskContext("task-1", "p1", 1L, CapabilityMode.ACCEPT, "task-1")

        val searchReq = CapabilityRequirement.Network("p1", setOf("api.search.brave.com"))
        val pending = broker.check(context, searchReq) as CapabilityCheck.NeedsApproval
        broker.grant(pending.request.requestId, CapabilityDecision.Allow(GrantScope.TASK))
        check(broker.check(context, searchReq) is CapabilityCheck.Allowed)

        // Revoke network capability
        broker.revokeTask(context.taskId, CapabilityRevocation.NETWORK)

        // Subsequent check must be denied / require approval again
        val postRevoke = broker.check(context, searchReq)
        check(postRevoke is CapabilityCheck.NeedsApproval) {
            "Expected NeedsApproval after revocation, got: $postRevoke"
        }
        println("  testBrokerRevocation: PASS")
    }

    private fun testBrokerFullTaskNetworkWorks() {
        val store = CapabilityStore.inMemory()
        val broker = CapabilityBrokerImpl(store, { null }, { MountState.AVAILABLE })
        val context = TaskContext("task-1", "p1", 1L, CapabilityMode.ACCEPT, "task-1")

        // Request full-task network (empty domains)
        val fullNetworkReq = CapabilityRequirement.Network("p1", emptySet())
        val pending = broker.check(context, fullNetworkReq) as CapabilityCheck.NeedsApproval
        broker.grant(pending.request.requestId, CapabilityDecision.Allow(GrantScope.TASK))

        // Any domain request is now granted
        val anyDomain = CapabilityRequirement.Network("p1", setOf("any-arbitrary-domain.org"))
        val checkResult = broker.check(context, anyDomain)
        check(checkResult is CapabilityCheck.Allowed) {
            "Full-task network access must grant arbitrary domain, got: $checkResult"
        }
        println("  testBrokerFullTaskNetworkWorks: PASS")
    }
}
