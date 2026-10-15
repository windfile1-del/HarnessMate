package dev.wind.agent.phone

import android.content.Context
import dev.wind.agent.AgentBus
import dev.wind.agent.CancellationToken
import org.json.JSONObject

/** User-controlled phone authority, independent of the coding AUTO switch. */
internal object PhoneApprovalPolicy {
    enum class Mode { SAFE, FULL }
    data class Ticket(val epoch: Long, val scope: Scope?, val fullyManaged: Boolean = false,
        val permissions: dev.wind.agent.permissions.AgentPermissions? = null, val permissionRevision: Long = 0L)
    data class Scope(val category: String, val target: String)
    private var epoch = 0L
    private fun prefs(context: Context) = context.getSharedPreferences("phone_automation", Context.MODE_PRIVATE)
    fun mode(context: Context) = if (dev.wind.agent.permissions.AgentPermissions.get(context).policy == dev.wind.agent.permissions.ActionPolicy.AUTONOMOUS) Mode.FULL else Mode.SAFE
    @Synchronized fun choose(context: Context, mode: Mode) {
        PhoneManagedRecovery.stopAll()
        prefs(context).edit().putString("approval_mode", mode.name).apply(); epoch++
        dev.wind.agent.permissions.AgentPermissions.get(context).configure(policy = if (mode == Mode.FULL) dev.wind.agent.permissions.ActionPolicy.AUTONOMOUS else dev.wind.agent.permissions.ActionPolicy.CONSERVATIVE)
    }
    @Synchronized fun clear() { epoch++ }
    fun count() = 0 // Backward-compatible status field; Full no longer uses scoped grants.
    fun endTask(owner: String) { PhoneManagedRecovery.endTask(owner); VirtualDisplays.endTask(owner) /* Persisted Full authority survives task end. */ }

    fun authorize(context: Context, owner: String, name: String, args: JSONObject, scope: Scope?, token: CancellationToken): Ticket? {
        token.throwIfCancelled()
        val initial = synchronized(this) { epoch }
        val permissions = dev.wind.agent.permissions.AgentPermissions.get(context)
        check(dev.wind.agent.permissions.AgentPermissionGate(context).error(name, args, owner) == null) { "AGENT_PERMISSION_REQUIRED" }
        val revision = permissions.revision
        // Explicit persisted user choice is authority for every phone effect, across task/turn/process restarts.
        if (permissions.policy == dev.wind.agent.permissions.ActionPolicy.AUTONOMOUS) return Ticket(initial, scope, true, permissions, revision)
        if (permissions.policy == dev.wind.agent.permissions.ActionPolicy.BALANCED && scope == null) return Ticket(initial, scope, false, permissions, revision)
        val reviewed = JSONObject(args.toString()).put("phone_permission_mode", "safe")
        if (!AgentBus.awaitApprovalDetailed(name, reviewed, token).approved) return null
        token.throwIfCancelled()
        synchronized(this) { check(epoch == initial) { "PHONE_AUTHORITY_CHANGED: retry under current mode" } }
        return Ticket(initial, scope, false, permissions, revision)
    }
    @Synchronized fun check(ticket: Ticket) {
        check(epoch == ticket.epoch) { "PHONE_AUTHORITY_REVOKED" }
        check(ticket.permissions == null || ticket.permissions.revision == ticket.permissionRevision) { "AGENT_PERMISSION_REVOKED" }
    }

    fun nativeScope(name: String, args: JSONObject): Scope? = when (name) {
        "phone_call" -> Scope("call", args.getString("number").filter { it.isDigit() || it == '+' } + ":" + args.optBoolean("direct", false))
        "phone_message" -> Scope("message", args.getString("number").filter { it.isDigit() || it == '+' } + ":" + args.optBoolean("direct", false) + ":sim=" + args.optInt("subscription_id", -1))
        else -> null
    }

    fun categories(pkg: String, action: String, args: JSONObject, target: NodeFacts?, page: String, visual: Boolean): Set<String> {
        // Explicit Full-mode dismiss is limited again at commit to a detected negative button.
        if (action == "dismiss" && !visual && target != null && PhoneInterruptions.negativeButton(target) &&
            args.optString("sensitivity", "normal") == "normal") return emptySet()
        return PhoneRisk.categories(pkg, action, args.optString("sensitivity", "normal"), args.optString("editor_action", "auto"), target, page, visual)
    }

    fun target(observation: PhoneControlService.Observation, args: JSONObject): NodeFacts? {
        val all = observation.nodes.values.map { it.facts }
        val action = args.optString("action")
        val node = if (action in setOf("tap", "long_press", "swipe")) {
            val x = normalized(args, "x", observation.width); val y = normalized(args, "y", observation.height)
            PhoneNodeMatcher.deepestAt(all, x, y)
        } else observation.nodes[args.optString("node_id")]?.facts
        return if (node != null && action in setOf("tap", "long_press", "click", "dismiss"))
            PhoneNodeMatcher.clickableOwner(node, all) ?: node else node
    }

    fun normalized(args: JSONObject, name: String, extent: Int): Int {
        val value = args.getDouble(name)
        require(value.isFinite() && value in 0.0..1000.0) { "PHONE_COORDINATE_INVALID" }
        return (value / 1000.0 * (extent - 1)).toInt()
    }

    fun pageScope(observation: PhoneControlService.Observation, args: JSONObject): Scope? {
        val node = target(observation, args)
        val page = observation.nodes.values.joinToString(" ") { it.facts.text + " " + it.facts.desc }
        val classes = categories(observation.packageName, args.getString("action"), args, node, page, observation.mode == PhoneAutomation.Mode.VISUAL)
        if (classes.isEmpty()) return null
        val identity = observation.packageName + ":" + (node?.resId?.takeIf { it.isNotBlank() } ?: node?.pathKey ?: args.getString("action"))
        return Scope(classes.sorted().joinToString("+"), identity)
    }

    /** Safe mode refuses new categories. User-enabled Full delegates all categories; geometry/protection still apply. */
    fun recheck(ticket: Ticket, pkg: String, action: String, args: JSONObject, target: NodeFacts?, live: List<NodeFacts>, visual: Boolean) {
        check(ticket)
        val now = categories(pkg, action, args, target, live.joinToString(" ") { it.text + " " + it.desc }, visual)
        val approved = ticket.scope?.category?.split("+")?.toSet().orEmpty()
        val extra = PhoneRisk.extra(approved, now, ticket.fullyManaged)
        check(extra.isEmpty()) { "PHONE_NODE_RISK_CHANGED: new categories=$extra; observe again and approve the new risk" }
    }
}
