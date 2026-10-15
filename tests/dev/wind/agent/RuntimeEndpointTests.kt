package dev.wind.agent

import dev.wind.agent.runtime.CompanionRuntimeClient
import dev.wind.agent.runtime.EmbeddedRuntimeEndpoint
import dev.wind.agent.runtime.ExternalCompanionEndpoint
import dev.wind.agent.runtime.RuntimeBinding
import dev.wind.agent.runtime.RuntimeProviderId
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Differential contract: external and embedded transports expose one result shape. */
object RuntimeEndpointTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val streamOpener = { _: RuntimeBinding, path: String, expectedSize: Long, _: String? ->
            val bytes = "stream:$path".toByteArray()
            check(expectedSize == bytes.size.toLong()) { "test stream size contract drifted" }
            ByteArrayInputStream(bytes)
        }
        val external = ExternalCompanionEndpoint(
            inProcessDispatcher = ::dispatch,
            inProcessStreamOpener = streamOpener
        )
        val embedded = EmbeddedRuntimeEndpoint(
            inProcessDispatcher = ::dispatch,
            inProcessStreamOpener = streamOpener
        )
        check(external.endpointId != embedded.endpointId)

        val left = CompanionRuntimeClient(endpointOverride = external)
        val right = CompanionRuntimeClient(endpointOverride = embedded)
        val leftBinding = left.openWorkspace("p", 7L, WORKSPACE)
        val rightBinding = right.openWorkspace("p", 7L, WORKSPACE)
        val leftProbe = left.probe()
        val rightProbe = right.probe()
        check(leftProbe.providerId == rightProbe.providerId &&
            leftProbe.state == rightProbe.state &&
            leftProbe.capabilities == rightProbe.capabilities &&
            leftProbe.runtimeInstanceId == rightProbe.runtimeInstanceId) {
            "probe parity failed"
        }
        check(leftBinding == rightBinding) { "workspace parity failed" }
        check(left.mirrorCall(leftBinding, "sync", JSONObject().put("workDir", "project")).toString() ==
            right.mirrorCall(rightBinding, "sync", JSONObject().put("workDir", "project")).toString()) {
            "mirror/workDir parity failed"
        }
        val streamPath = "src/Main.kt"
        val expectedStream = "stream:$streamPath".toByteArray()
        val leftStream = left.openFileStream(
            leftBinding, streamPath, "stream-1", expectedStream.size.toLong(), 64 * 1024L
        ).use { it.readBytes() }
        val rightStream = right.openFileStream(
            rightBinding, streamPath, "stream-1", expectedStream.size.toLong(), 64 * 1024L
        ).use { it.readBytes() }
        check(leftStream.contentEquals(expectedStream) && rightStream.contentEquals(expectedStream)) {
            "openFileStream parity failed"
        }
        val leftExecution = left.startCommand(leftBinding, listOf("echo", "ok"))
        val rightExecution = right.startCommand(rightBinding, listOf("echo", "ok"))
        check(leftExecution == rightExecution)
        check(left.cancel(leftBinding, leftExecution) == right.cancel(rightBinding, rightExecution))
        println("RuntimeEndpointTests: OK")
    }

    private const val WORKSPACE = "0123456789abcdef01234567"

    private fun dispatch(code: Int, payload: String): String {
        val request = JSONObject(payload)
        return when (code) {
            CompanionRuntimeClient.TRANSACTION_PROBE -> JSONObject()
                .put("ok", true)
                .put("providerId", RuntimeProviderId.VEGA_COMPANION.name)
                .put("runtimeInstanceId", "test-instance")
                .put("capabilities", JSONObject()
                    .put("commandExecution", true)
                    .put("persistentWorkspace", true)
                    .put("processGroupCancellation", true)
                    .put("mirrorProtocolVersion", 2)
                    .put("toolchains", JSONObject())
                    .put("languageServers", JSONArray())
                    .put("supportedAbis", JSONArray())
                    .put("runtimeVersion", "test"))
                .toString()
            CompanionRuntimeClient.TRANSACTION_OPEN_WORKSPACE -> JSONObject()
                .put("ok", true)
                .put("binding", RuntimeBinding(
                    RuntimeProviderId.VEGA_COMPANION,
                    request.getString("workspaceId"),
                    request.getString("projectId"),
                    request.getLong("rootGeneration"),
                    "test-instance",
                    protocolVersion = 2,
                    layoutVersion = 1
                ).toJson())
                .toString()
            CompanionRuntimeClient.TRANSACTION_START_COMMAND -> JSONObject()
                .put("ok", true).put("executionId", "execution-1").toString()
            CompanionRuntimeClient.TRANSACTION_CANCEL -> JSONObject()
                .put("ok", true).put("cancelled", true).put("cleanupConfirmed", true).toString()
            CompanionRuntimeClient.TRANSACTION_MIRROR_CALL -> JSONObject()
                .put("ok", true).put("operation", request.getString("operation"))
                .put("workDir", request.optJSONObject("args")?.optString("workDir", "") ?: "")
                .toString()
            else -> JSONObject().put("ok", true).toString()
        }
    }
}
