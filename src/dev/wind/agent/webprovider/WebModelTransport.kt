package dev.wind.agent.webprovider

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.Message
import dev.wind.agent.model.ModelRequest
import dev.wind.agent.model.ModelStreamCallback
import dev.wind.agent.model.ModelTransport
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** One browser exchange, no LLM/tool loop and no reverse-engineered service API. */
class WebModelTransport(context: Context) : ModelTransport {
    private val app = context.applicationContext
    private val prefs = WebProviderPrefs(app)
    private val store = WebProviderStore(app)
    private val browser = BrowserSessionManager.get(app)

    override fun stream(request: ModelRequest, token: CancellationToken, callback: ModelStreamCallback) {
        val binding = request.chat.webBinding ?: return callback.onError("WEB_BINDING_REQUIRED")
        val provider = WebProviderRegistry.get(binding.providerId) ?: return callback.onError("WEB_PROVIDER_UNKNOWN")
        if (!prefs.runnable(provider.id)) {
            callback.onError(if (provider.manualOnly) "PROVIDER_MANUAL_ONLY" else "WEB_PROVIDER_DISABLED_OR_NOT_ACKNOWLEDGED")
            return
        }
        val question = synchronized(request.chat.messages) { request.chat.messages.lastOrNull { it.role == "user" } }
            ?: return callback.onError("USER_QUESTION_REQUIRED")
        val operation = try { WebOperation.valueOf(question.webOperation) } catch (_: Exception) { WebOperation.SEND }
        var session: BrowserSessionManager.Session? = null
        var receipt: WebSendReceipt? = null
        var submitted = false
        var acquired = false
        val stager = WebUploadStager(app)
        var stopped = false
        try {
            token.throwIfCancelled()
            WebUploadStager.clearExpired(app)
            if (binding.profileName != prefs.profile(provider.id)) throw IllegalStateException("PROFILE_BINDING_EXPIRED_SELECT_PROVIDER_AGAIN")
            val history = if (binding.includeHistory) synchronized(request.chat.messages) {
                request.chat.messages.filter { it !== question && it.role in setOf("user", "assistant") && !it.modelOnly && !it.isError && !it.isStep && it.content.isNotBlank() }
                    .takeLast(8).map { it.role to it.content }
            } else emptyList()
            val context = question.webContexts.toMutableList()
            question.screenContextNote?.let { context.add(WebContextItem("screen_text", "Screenshot metadata", it)) }
            val compiled = WebPromptCompiler.compile(question.textForModel(), context, history, prefs.contextLimit())
            var prompt = compiled.text
            if (compiled.truncated.isNotEmpty()) callback.onState("CONTEXT_TRUNCATED")
            if (question.webRequestId.isNullOrBlank()) question.webRequestId = UUID.randomUUID().toString()
            dev.wind.agent.ChatStore(app).saveNow(request.chat)
            val requestId = question.webRequestId!!
            val previous = store.read(request.chat.id)
            if (previous != null && previous.profileName == binding.profileName && previous.providerId == provider.id) {
                if (previous.requestId == requestId && previous.state == WebRequestState.COMPLETE) {
                    callback.onSnapshot(previous.answer); callback.onDone(previous.answer); return
                }
                if (previous.state in setOf(WebRequestState.SUBMITTING, WebRequestState.SUBMITTED, WebRequestState.GENERATING, WebRequestState.UNKNOWN)) {
                    callback.onError("PREVIOUS_WEB_SEND_UNCONFIRMED_OPEN_ORIGINAL_PAGE"); return
                }
            }
            val s = browser.onMain(token) { browser.open(provider, binding, request.chat.id) }
            session = s
            browser.onMain(token) { browser.acquire(s, requestId) }
            acquired = true
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15)
            var probe = ready(s, token, callback, deadline)
            if (binding.remoteUrl == null && (probe.optInt("userCount") > 0 || probe.optInt("answerCount") > 0)) {
                command(s, "newConversation", JSONObject(), token)
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                do {
                    pause(token, 400)
                    probe = ready(s, token, callback, deadline)
                } while ((probe.optInt("userCount") > 0 || probe.optInt("answerCount") > 0) && System.nanoTime() < end)
                check(probe.optInt("userCount") == 0 && probe.optInt("answerCount") == 0) { "NEW_CONVERSATION_UNCONFIRMED" }
            }
            if (operation == WebOperation.REGENERATE) {
                check(provider.capabilities.regenerate && probe.optJSONObject("capabilities")?.optBoolean("regenerate") == true) { "REGENERATE_UNSUPPORTED" }
                val original = probe.optString("lastUserText")
                check(original.isNotBlank() && original.replace(Regex("\\s+"), " ").trim()
                    .endsWith(question.textForModel().replace(Regex("\\s+"), " ").trim())) { "ORIGINAL_QUESTION_UNCONFIRMED" }
                prompt = original
            }
            check(operation != WebOperation.EDIT_AND_SEND) { "EDIT_REQUIRES_NEW_CONVERSATION_BRANCH" }
            receipt = WebSendReceipt(requestId, request.chat.id, provider.id, binding.profileName, operation, WebDigests.of(prompt))
            store.write(receipt)
            if (operation == WebOperation.SEND) {
                val inputs = question.attachments.toMutableList()
                question.screenContext?.let { inputs.add(it) }
                if (inputs.any { it.kind == "image" }) check(provider.capabilities.images) { "IMAGE_INPUT_UNSUPPORTED_SWITCH_PROVIDER" }
                if (inputs.any { it.kind != "image" }) check(provider.capabilities.files) { "FILE_INPUT_UNSUPPORTED_SWITCH_PROVIDER" }
                val uploads = stager.stage(inputs, token)
                if (uploads.isNotEmpty()) {
                    browser.onMain(token) { s.uploads = uploads; s.chooserConsumed = false; s.approvedAttachments = false }
                    val uploadArgs = JSONObject().put("names", org.json.JSONArray(uploads.map { it.name }))
                    // Menus may create a hidden file input only after the first click.
                    var action = browser.evaluate(s, "upload", uploadArgs, token)
                    if (action.optBoolean("menuOpened")) {
                        val menuEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        uploadArgs.put("continuation", true)
                        do {
                            pause(token, 250)
                            action = browser.evaluate(s, "upload", uploadArgs, token)
                        } while (action.optString("error") == "UPLOAD_CONTROL_NOT_FOUND" && System.nanoTime() < menuEnd)
                    }
                    val uploadError = action.optString("error")
                    if (uploadError == "UPLOAD_CONTROL_NOT_FOUND") {
                        // The user's click can open the chooser; it still receives only these staged files.
                        takeover(s, uploadError, token, callback, deadline)
                        check(s.chooserConsumed && s.approvedAttachments) { "UPLOAD_UNCONFIRMED" }
                    } else check(uploadError.isEmpty()) { uploadError }
                    val uploadEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                    var uploadProbe: JSONObject
                    do {
                        pause(token, 400); uploadProbe = ready(s, token, callback, deadline)
                        s.error?.let { throw IllegalStateException(it) }
                    } while ((!s.chooserConsumed || (!uploadProbe.optBoolean("uploadReady") && !s.approvedAttachments)) && System.nanoTime() < uploadEnd)
                    if (!s.chooserConsumed || (!uploadProbe.optBoolean("uploadReady") && !s.approvedAttachments)) {
                        takeover(s, "UPLOAD_UNCONFIRMED", token, callback, deadline)
                        check(s.chooserConsumed && s.approvedAttachments) { "UPLOAD_UNCONFIRMED" }
                    }
                }
            }
            token.throwIfCancelled()
            check(prefs.runnable(provider.id)) { "WEB_PROVIDER_DISABLED" }
            val args = JSONObject().put("requestId", requestId).put("text", prompt).put("operation", operation.name)
            if (operation == WebOperation.REGENERATE) {
                receipt.state = WebRequestState.SUBMITTING; store.write(receipt)
                submitted = true
                command(s, "begin", args, token)
            } else {
                command(s, "begin", args, token)
                // Wait for the editor framework to enable its button before recording a possible send.
                val sendEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var sendReady: Boolean
                do {
                    pause(token, 180)
                    sendReady = command(s, "probe", JSONObject(), token).optJSONObject("capabilities")?.optBoolean("send") == true
                } while (!sendReady && System.nanoTime() < sendEnd)
                receipt.state = WebRequestState.SUBMITTING; store.write(receipt)
                submitted = true
                if (sendReady) command(s, "commit", args, token)
                else {
                    command(s, "observeManualSend", args, token)
                    takeover(s, "SEND_CONTROL_NOT_FOUND", token, callback, deadline)
                    // Continue observation of this baseline. Never click after a user's manual send.
                }
            }
            receipt.state = WebRequestState.SUBMITTED; store.write(receipt)
            binding.includeHistory = false
            val confirmedEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
            var confirmed = false
            var lastOutput = ""
            var lastProgress = System.nanoTime()
            var lastCheckpoint = 0L
            while (System.nanoTime() < deadline) {
                if (token.isCancelled || callback.isCancelled()) { stopped = true; break }
                check(prefs.runnable(provider.id)) { "WEB_PROVIDER_DISABLED" }
                var snapshot = ready(s, token, callback, deadline)
                // A confirmed request may survive page reload. Reattach observation only;
                // recover never prepares, clicks or re-sends the question.
                if (confirmed && snapshot.optString("requestId") != requestId && binding.remoteUrl != null) {
                    snapshot = command(s, "recover", args, token)
                }
                confirmed = confirmed || snapshot.optBoolean("userConfirmed")
                if (!confirmed && System.nanoTime() > confirmedEnd) throw IllegalStateException("SEND_UNCONFIRMED")
                if (snapshot.optBoolean("generationFailed")) throw IllegalStateException("GENERATION_FAILED")
                val answer = snapshot.optString("answer")
                if (answer != lastOutput && answer.isNotEmpty()) {
                    lastOutput = answer; receipt.answer = answer; lastProgress = System.nanoTime()
                    callback.onSnapshot(answer)
                }
                if (confirmed) {
                    receipt.state = WebRequestState.GENERATING
                    remember(binding, snapshot.optString("url"), provider)
                    if (receipt.remoteUrl != binding.remoteUrl) dev.wind.agent.ChatStore(app).saveNow(request.chat)
                    receipt.remoteUrl = binding.remoteUrl; receipt.responseId = snapshot.optString("answerId")
                }
                if (System.nanoTime() - lastCheckpoint > TimeUnit.SECONDS.toNanos(2)) {
                    store.write(receipt); lastCheckpoint = System.nanoTime()
                }
                if (confirmed && snapshot.optBoolean("done") && answer.isNotBlank()) {
                    receipt.state = WebRequestState.COMPLETE; store.write(receipt)
                    prefs.status(provider.id, "READY_EXPERIMENTAL"); prefs.markRecent(provider.id)
                    callback.onDone(answer); return
                }
                if (System.nanoTime() - lastProgress > TimeUnit.MINUTES.toNanos(3)) throw IllegalStateException("GENERATION_STALLED_OR_UNFINISHED")
                pause(token, 400)
            }
            if (!stopped) throw IllegalStateException("WEB_REQUEST_TIMEOUT")
        } catch (_: CancellationToken.CancelledException) {
            stopped = true
        } catch (failure: Throwable) {
            val error = diagnostic(failure)
            receipt?.let { it.state = if (submitted) WebRequestState.UNKNOWN else WebRequestState.FAILED; it.diagnostic = error; safelyWrite(it) }
            prefs.status(provider.id, error)
            callback.onError(error)
        } finally {
            val s = session
            if (s != null && acquired) {
                if (stopped || (submitted && receipt?.state != WebRequestState.COMPLETE)) {
                    val cleanupToken = CancellationToken()
                    try {
                        command(s, "stop", JSONObject(), cleanupToken)
                        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        var stillGenerating: Boolean
                        do {
                            stillGenerating = command(s, "probe", JSONObject(), cleanupToken).optBoolean("generating")
                            if (stillGenerating) cleanupToken.sleep(250)
                        } while (stillGenerating && System.nanoTime() < end)
                        if (stopped) receipt?.let {
                            it.state = if (stillGenerating) WebRequestState.UNKNOWN else WebRequestState.STOPPED
                            it.diagnostic = if (stillGenerating) "STOP_UNCONFIRMED" else "STOPPED_BY_USER"; safelyWrite(it)
                            if (stillGenerating) callback.onError("STOP_UNCONFIRMED_OPEN_ORIGINAL_PAGE")
                        }
                    } catch (_: Throwable) {
                        if (stopped) receipt?.let { it.state = WebRequestState.UNKNOWN; it.diagnostic = "STOP_UNCONFIRMED"; safelyWrite(it) }
                    }
                }
                try { browser.onMain(CancellationToken()) { browser.release(s) } } catch (_: Throwable) {}
            }
            stager.close()
            if (stopped) callback.onDone(receipt?.answer ?: "")
        }
    }

    private fun remember(binding: WebConversationBinding, rawUrl: String, provider: WebProviderDefinition) {
        val sanitized = WebUrlPolicy.sessionUrl(rawUrl, provider) ?: return
        binding.remoteUrl = sanitized; binding.updatedAt = System.currentTimeMillis()
        val uri = android.net.Uri.parse(sanitized)
        binding.remoteId = listOf("chat", "chat_id", "conversation_id", "session_id", "id").mapNotNull { uri.getQueryParameter(it) }.firstOrNull()
            ?: uri.pathSegments.lastOrNull()?.takeIf { it.length >= 8 && it !in setOf("app", "chat", "fixture") }
        binding.remoteId = binding.remoteId?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,180}")) }
        binding.adapterVersion = WebProviderRegistry.ADAPTER_VERSION
    }
    private fun command(s: BrowserSessionManager.Session, name: String, args: JSONObject, token: CancellationToken): JSONObject {
        val result = browser.evaluate(s, name, args, token)
        result.optString("error").takeIf { it.isNotEmpty() }?.let { throw IllegalStateException(it) }
        return result
    }
    private fun ready(s: BrowserSessionManager.Session, token: CancellationToken, callback: ModelStreamCallback, deadline: Long): JSONObject {
        val loadingDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(60))
        while (System.nanoTime() < deadline) {
            token.throwIfCancelled(); s.error?.let { throw IllegalStateException(it) }
            if (s.takeover) { pause(token, 400); continue }
            val owns = browser.onMain(token) { s.provider.owns(s.view.url) }
            if (s.state == "LOADING" || !owns) {
                check(System.nanoTime() < loadingDeadline) { "PAGE_LOAD_TIMEOUT" }
                if (!owns && s.state != "LOADING") takeover(s, "LOGIN_REQUIRED", token, callback, deadline)
                else pause(token, 400)
                continue
            }
            val result = command(s, "probe", JSONObject(), token)
            when (val status = result.optString("status")) {
                "READY" -> { prefs.status(s.provider.id, "READY_EXPERIMENTAL"); return result }
                "RATE_LIMITED" -> throw IllegalStateException(status)
                "LOGIN_REQUIRED", "HUMAN_VERIFICATION_REQUIRED", "INPUT_NOT_FOUND" -> takeover(s, status, token, callback, deadline)
                else -> throw IllegalStateException("ADAPTER_INCOMPATIBLE")
            }
        }
        throw IllegalStateException("PAGE_NOT_READY_TIMEOUT")
    }
    private fun takeover(s: BrowserSessionManager.Session, state: String, token: CancellationToken, callback: ModelStreamCallback, deadline: Long) {
        s.state = state; prefs.status(s.provider.id, state); callback.onState(state)
        val start = s.resumed
        // Bring the same browser to the user instead of silently waiting for five minutes.
        browser.onMain(token) {
            if (s.activity == null) try { WebProviderBrowserActivity.open(app, s.provider.id, s.localChatId) }
            catch (_: Exception) { /* Composer still exposes the manual login/takeover entry. */ }
        }
        val end = minOf(deadline, System.nanoTime() + TimeUnit.MINUTES.toNanos(5))
        while (s.resumed == start && System.nanoTime() < end) { token.throwIfCancelled(); pause(token, 400) }
        check(s.resumed != start) { "TAKEOVER_TIMEOUT_OPEN_ORIGINAL_PAGE" }
        s.state = "PAGE_LOADED"
    }
    private fun pause(token: CancellationToken, ms: Long) { if (!token.sleep(ms)) throw CancellationToken.CancelledException() }
    private fun safelyWrite(receipt: WebSendReceipt) { try { store.write(receipt) } catch (_: Throwable) {} }
    private fun diagnostic(failure: Throwable): String {
        val message = failure.message ?: "WEB_PROVIDER_FAILURE"
        // Never expose exception URLs, page text, credentials or physical paths.
        return if (message.matches(Regex("[A-Z][A-Z0-9_]{1,120}"))) message else "WEB_PROVIDER_FAILURE_${failure.javaClass.simpleName.take(40)}"
    }
}
