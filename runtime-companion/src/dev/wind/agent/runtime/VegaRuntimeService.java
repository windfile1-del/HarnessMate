package dev.wind.agent.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Minimal, signature-gated Binder endpoint for the self-contained runtime.
 *
 * P6.2 deliberately proves the component, binding identity, private workspace
 * layout, command supervisor and cancellation lifecycle. Mirror/LSP providers
 * are subsequent P6 phases; this service never silently falls back to Termux.
 */
public class VegaRuntimeService extends Service {
    public static final String ACTION_BIND = "dev.wind.agent.runtime.BIND";
    public static final String BIND_PERMISSION = "dev.wind.agent.runtime.BIND_RUNTIME";
    public static final String DESCRIPTOR = "dev.wind.agent.runtime.IVegaRuntime";
    public static final int PROTOCOL_VERSION = RuntimeWorkspace.PROTOCOL_VERSION;
    public static final int LAYOUT_VERSION = RuntimeWorkspace.LAYOUT_VERSION;

    public static final int TRANSACTION_PROBE = IBinder.FIRST_CALL_TRANSACTION;
    public static final int TRANSACTION_OPEN_WORKSPACE = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int TRANSACTION_RUN_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int TRANSACTION_CANCEL = IBinder.FIRST_CALL_TRANSACTION + 3;
    public static final int TRANSACTION_QUERY = IBinder.FIRST_CALL_TRANSACTION + 4;
    public static final int TRANSACTION_RECONCILE = IBinder.FIRST_CALL_TRANSACTION + 5;
    public static final int TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 6;
    public static final int TRANSACTION_START_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 7;
    public static final int TRANSACTION_AWAIT_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 8;
    public static final int TRANSACTION_MIRROR_CALL = IBinder.FIRST_CALL_TRANSACTION + 9;
    public static final int TRANSACTION_POLL_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 10;
    /**
     * Mirror data-plane transactions. Additive on protocol 2: a durable binding
     * is never silently upgraded, so the new plane is announced by the probe
     * rather than by a version bump, and a host that does not see the capability
     * refuses the execution instead of discovering the gap halfway through a
     * sync.
     */
    public static final int TRANSACTION_OPEN_MIRROR_READ = IBinder.FIRST_CALL_TRANSACTION + 11;
    public static final int TRANSACTION_SYNC_BEGIN = IBinder.FIRST_CALL_TRANSACTION + 12;
    public static final int TRANSACTION_SYNC_PLAN_PAGE = IBinder.FIRST_CALL_TRANSACTION + 13;
    public static final int TRANSACTION_SYNC_FILE = IBinder.FIRST_CALL_TRANSACTION + 14;
    public static final int TRANSACTION_SYNC_COMMIT = IBinder.FIRST_CALL_TRANSACTION + 15;
    public static final int TRANSACTION_SYNC_ABORT = IBinder.FIRST_CALL_TRANSACTION + 16;
    public static final int TRANSACTION_OPEN_EXECUTION_LOG = IBinder.FIRST_CALL_TRANSACTION + 17;
    public static final int TRANSACTION_OPEN_EXECUTION_OUTPUT = IBinder.FIRST_CALL_TRANSACTION + 18;

    protected RuntimeWorkspace workspace;
    private RuntimeProcessSupervisor supervisor;
    private final IBinder binder = new RuntimeBinder();
    private final ThreadLocal<IBinder> commandOwner = new ThreadLocal<>();

    @Override public void onCreate() {
        super.onCreate();
        workspace = createWorkspace();
        supervisor = createSupervisor();
    }

    protected RuntimeWorkspace createWorkspace() { return new RuntimeWorkspace(getFilesDir()); }
    protected RuntimeProcessSupervisor createSupervisor() { return new RuntimeProcessSupervisor(); }
    protected String providerId() { return "VEGA_COMPANION"; }
    protected JSONObject runtimeCapabilities() throws JSONException { return null; }
    protected boolean runtimeReady() { return true; }
    protected String runtimeDiagnostic() { return ""; }

    @Override public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION_BIND.equals(intent.getAction())) return null;
        return binder;
    }

    @Override public void onDestroy() {
        RuntimeProcessSupervisor current = supervisor;
        if (current != null) current.close();
        super.onDestroy();
    }

    private final class RuntimeBinder extends Binder {
        RuntimeBinder() {
            attachInterface(null, DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply,
                                               int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code < TRANSACTION_PROBE || code > TRANSACTION_OPEN_EXECUTION_OUTPUT) {
                return super.onTransact(code, data, reply, flags);
            }
            // The descriptor-carrying transactions answer with a descriptor of
            // their own, so they own the reply framing from the first byte.
            if (code == TRANSACTION_OPEN_MIRROR_READ) {
                return transactOpenMirrorRead(data, reply);
            }
            if (code == TRANSACTION_OPEN_EXECUTION_LOG || code == TRANSACTION_OPEN_EXECUTION_OUTPUT)
                return transactOpenExecutionLog(data, reply, code == TRANSACTION_OPEN_EXECUTION_OUTPUT);
            data.enforceInterface(DESCRIPTOR);
            String payload = data.readString();
            if (code == TRANSACTION_SYNC_PLAN_PAGE || code == TRANSACTION_SYNC_FILE) {
                ParcelFileDescriptor source = readDescriptor(data);
                String response;
                try {
                    response = dispatchSource(code, payload == null ? "{}" : payload, source);
                } finally {
                    closeQuietly(source);
                }
                reply.writeNoException();
                reply.writeString(response);
                return true;
            }
            IBinder owner = code == TRANSACTION_START_COMMAND && data.dataAvail() > 0 ? data.readStrongBinder() : null;
            String response;
            commandOwner.set(owner);
            try { response = dispatch(code, payload == null ? "{}" : payload); }
            finally { commandOwner.remove(); }
            reply.writeNoException();
            reply.writeString(response);
            return true;
        }
    }

    private String dispatch(int code, String payload) {
        try {
            JSONObject request = new JSONObject(payload);
            switch (code) {
                case TRANSACTION_PROBE: return probe();
                case TRANSACTION_OPEN_WORKSPACE: return openWorkspace(request);
                case TRANSACTION_RUN_COMMAND: return runCommand(request);
                case TRANSACTION_CANCEL: return cancel(request);
                case TRANSACTION_QUERY: return query(request);
                case TRANSACTION_RECONCILE: return reconcile(request);
                case TRANSACTION_STOP: return stop();
                case TRANSACTION_START_COMMAND: return startCommand(request);
                case TRANSACTION_AWAIT_COMMAND: return awaitCommand(request);
                case TRANSACTION_MIRROR_CALL: return mirrorCall(request);
                case TRANSACTION_POLL_COMMAND: return pollCommand(request);
                case TRANSACTION_SYNC_BEGIN: return syncBegin(request);
                case TRANSACTION_SYNC_COMMIT: return syncCommit(request);
                case TRANSACTION_SYNC_ABORT: return syncAbort(request);
                default: return error("RUNTIME_TRANSACTION_UNKNOWN");
            }
        } catch (JSONException malformed) {
            return error("RUNTIME_REQUEST_INVALID");
        } catch (RuntimeException rejected) {
            return error(safeErrorCode(rejected.getMessage()));
        }
    }

    /**
     * Dispatch for the transactions that carry an inbound file descriptor. The
     * payload stream is read here and released by the caller, so a body that
     * throws cannot leak the read end of the pipe.
     */
    private String dispatchSource(int code, String payload, ParcelFileDescriptor source) {
        try {
            JSONObject request = new JSONObject(payload);
            switch (code) {
                case TRANSACTION_SYNC_PLAN_PAGE: return syncPlanPage(request, source);
                case TRANSACTION_SYNC_FILE: return syncFile(request, source);
                default: return error("RUNTIME_TRANSACTION_UNKNOWN");
            }
        } catch (JSONException malformed) {
            return error("RUNTIME_REQUEST_INVALID");
        } catch (RuntimeException rejected) {
            return error(safeErrorCode(rejected.getMessage()));
        }
    }

    /**
     * The download direction: metadata in the reply, bytes through a
     * descriptor.
     *
     * This transaction deliberately does not use the shared envelope. A reply
     * that carries a file descriptor must write it with
     * PARCELABLE_WRITE_RETURN_VALUE, which is a different framing from the
     * writeString() replies every other transaction uses, and mixing the two in
     * one reply would make the descriptor's ownership ambiguous if anything
     * later in the reply throws.
     */
    private boolean transactOpenMirrorRead(Parcel data, Parcel reply) throws RemoteException {
        ParcelFileDescriptor descriptor = null;
        String response;
        try {
            data.enforceInterface(DESCRIPTOR);
            String payload = data.readString();
            JSONObject request = new JSONObject(payload == null ? "{}" : payload);
            String projectId = request.getString("projectId");
            long generation = request.getLong("rootGeneration");
            String workspaceId = request.getString("workspaceId");
            String instanceId = request.getString("runtimeInstanceId");
            workspace.requireBound(projectId, generation, workspaceId, instanceId);
            JSONObject metadata = workspace.openReadFile(
                    workspaceId,
                    request.getString("path"),
                    request.optLong("expectedSize", -1L),
                    request.optLong("maxBytes", -1L));
            descriptor = ParcelFileDescriptor.open(
                    new File(metadata.getString("path")),
                    ParcelFileDescriptor.MODE_READ_ONLY);
            response = new JSONObject()
                    .put("ok", true)
                    .put("size", metadata.getLong("size"))
                    .toString();
        } catch (JSONException malformed) {
            response = error("RUNTIME_REQUEST_INVALID");
        } catch (RuntimeException rejected) {
            response = error(safeErrorCode(rejected.getMessage()));
        } catch (IOException unavailable) {
            response = error("RUNTIME_MIRROR_READ_FAILED");
        }
        reply.writeNoException();
        reply.writeString(response);
        if (descriptor == null) {
            reply.writeInt(0);
            return true;
        }
        try {
            reply.writeInt(1);
            descriptor.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        } catch (RuntimeException failure) {
            closeQuietly(descriptor);
            throw failure;
        }
        // The host owns the descriptor now; the reply object it was written to
        // is not the place to close it from.
        return true;
    }

    private boolean transactOpenExecutionLog(Parcel data, Parcel reply, boolean follow) throws RemoteException {
        ParcelFileDescriptor descriptor = null;
        String response;
        try {
            data.enforceInterface(DESCRIPTOR);
            JSONObject request = new JSONObject(data.readString());
            String project = request.getString("projectId");
            long generation = request.getLong("rootGeneration");
            String id = request.getString("workspaceId"), instance = request.getString("runtimeInstanceId");
            workspace.requireBound(project, generation, id, instance);
            File log = supervisor.executionLog(request.getString("executionId"),
                    new RuntimeProcessSupervisor.BindingScope(project, generation, id, instance), request.getString("stream"), follow);
            descriptor = ParcelFileDescriptor.open(log, ParcelFileDescriptor.MODE_READ_ONLY);
            response = new JSONObject().put("ok", true).put("size", log.length())
                    .put("byteLimit", RuntimeProcessSupervisor.MAX_LOG_BYTES).toString();
        } catch (JSONException malformed) { response = error("RUNTIME_REQUEST_INVALID"); }
        catch (RuntimeException rejected) { response = error(safeErrorCode(rejected.getMessage())); }
        catch (IOException unavailable) { response = error("RUNTIME_LOG_UNAVAILABLE"); }
        reply.writeNoException(); reply.writeString(response);
        if (descriptor == null) { reply.writeInt(0); return true; }
        try { reply.writeInt(1); descriptor.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE); }
        catch (RuntimeException failure) { closeQuietly(descriptor); throw failure; }
        return true;
    }

    private static ParcelFileDescriptor readDescriptor(Parcel data) {
        if (data.readInt() != 1) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PAYLOAD_MISSING");
        }
        return ParcelFileDescriptor.CREATOR.createFromParcel(data);
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static String safeErrorCode(String message) {
        if (message != null && message.matches("RUNTIME_[A-Z0-9_]+")) return message;
        return "RUNTIME_REQUEST_REJECTED";
    }

    private String probe() throws JSONException {
        JSONObject capabilities = runtimeCapabilities();
        if (capabilities == null) capabilities = new JSONObject()
                .put("commandExecution", true)
                .put("persistentWorkspace", true)
                .put("processGroupCancellation", RuntimeProcessSupervisor.supportsProcessGroupCancellation())
                .put("mirrorProtocolVersion", 2)
                .put("mirrorStreamProtocolVersion", RuntimeWorkspace.MIRROR_STREAM_PROTOCOL_VERSION)
                .put("helperPythonVersion", "none")
                .put("toolchains", probePackagedToolchains())
                .put("languageServers", new JSONArray())
                .put("networkIsolation", "POLICY_ONLY")
                .put("supportedAbis", new JSONArray())
                .put("runtimeVersion", "p6.2-core");
        return new JSONObject()
                .put("ok", true)
                .put("providerId", providerId())
                .put("ready", runtimeReady())
                .put("diagnostic", workspace.scrub(runtimeDiagnostic()))
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("layoutVersion", LAYOUT_VERSION)
                .put("runtimeInstanceId", workspace.runtimeInstanceId())
                .put("capabilities", capabilities)
                .put("activeExecutions", supervisor.activeCount())
                .toString();
    }

    private JSONObject probePackagedToolchains() {
        // Keep the standalone Java Companion build independent from host-only
        // Kotlin providers. The embedded APK supplies this functional probe.
        try {
            Class<?> inventory = Class.forName("dev.wind.agent.runtime.EmbeddedRuntimeInventory");
            Object result = inventory.getMethod("probe", android.content.Context.class).invoke(null, this);
            return result instanceof JSONObject ? (JSONObject) result : new JSONObject();
        } catch (ReflectiveOperationException | LinkageError failure) {
            return new JSONObject();
        }
    }

    private String openWorkspace(JSONObject request) throws JSONException {
        if (!runtimeReady()) throw new IllegalStateException("RUNTIME_PROVIDER_UNAVAILABLE");
        String projectId = request.getString("projectId");
        long generation = request.getLong("rootGeneration");
        String workspaceId = request.getString("workspaceId");
        RuntimeWorkspace.Binding binding = workspace.open(projectId, generation, workspaceId);
        return new JSONObject()
                .put("ok", true)
                .put("binding", binding.toJson())
                .put("workspaceId", workspaceId)
                .put("layout", "runtime-root/workspaces/" + workspaceId
                        + "/{project,home,tmp,cache,imports,.vega}")
                .toString();
    }

    private String runCommand(JSONObject request) throws JSONException {
        String projectId = request.getString("projectId");
        long generation = request.getLong("rootGeneration");
        String workspaceId = request.getString("workspaceId");
        String instanceId = request.getString("runtimeInstanceId");
        workspace.requireBound(projectId, generation, workspaceId, instanceId);

        JSONArray array = request.getJSONArray("argv");
        if (array.length() == 0 || array.length() > RuntimeProcessSupervisor.MAX_ARGUMENTS) {
            throw new IllegalArgumentException("RUNTIME_ARGUMENT_LIMIT");
        }
        List<String> argv = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) argv.add(array.getString(i));

        Map<String, String> environment = new HashMap<>();
        JSONObject env = request.optJSONObject("env");
        if (env != null) {
            java.util.Iterator<String> keys = env.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                environment.put(key, env.getString(key));
            }
        }
        long timeout = request.optLong("timeoutMs", 30000L);
        RuntimeProcessSupervisor.BindingScope scope = new RuntimeProcessSupervisor.BindingScope(
                projectId, generation, workspaceId, instanceId);
        File workingDirectory = workspace.resolveWorkingDirectory(
                workspaceId, request.optString("workDir", ""));
        RuntimeProcessSupervisor.Execution execution = supervisor.start(
                scope, argv, workingDirectory, environment, timeout,
                request.has("stdin") && !request.isNull("stdin") ? request.getString("stdin") : null);
        watchOwner(execution);
        RuntimeProcessSupervisor.Result result = supervisor.await(execution, timeout + 3000L);
        return new JSONObject()
                .put("ok", result.success())
                .put("executionId", result.executionId)
                .put("state", "TERMINATED")
                .put("exitCode", result.exitCode)
                .put("stdout", workspace.scrub(result.stdout))
                .put("stderr", workspace.scrub(result.stderr))
                .put("durationMs", result.durationMs)
                .put("timedOut", result.timedOut)
                .put("cancelled", result.cancelled)
                .put("cleanupConfirmed", result.cleanupConfirmed)
                .put("outputTruncated", result.outputTruncated)
                .put("failure", workspace.scrub(result.failure))
                .toString();
    }

    private String startCommand(JSONObject request) throws JSONException {
        String projectId = request.getString("projectId");
        long generation = request.getLong("rootGeneration");
        String workspaceId = request.getString("workspaceId");
        String instanceId = request.getString("runtimeInstanceId");
        workspace.requireBound(projectId, generation, workspaceId, instanceId);

        JSONArray array = request.getJSONArray("argv");
        if (array.length() == 0 || array.length() > RuntimeProcessSupervisor.MAX_ARGUMENTS) {
            throw new IllegalArgumentException("RUNTIME_ARGUMENT_LIMIT");
        }
        List<String> argv = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) argv.add(array.getString(i));

        Map<String, String> environment = new HashMap<>();
        JSONObject env = request.optJSONObject("env");
        if (env != null) {
            java.util.Iterator<String> keys = env.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                environment.put(key, env.getString(key));
            }
        }
        long timeout = request.optLong("timeoutMs", 30000L);
        RuntimeProcessSupervisor.BindingScope scope = new RuntimeProcessSupervisor.BindingScope(
                projectId, generation, workspaceId, instanceId);
        File workingDirectory = workspace.resolveWorkingDirectory(
                workspaceId, request.optString("workDir", ""));
        RuntimeProcessSupervisor.Execution execution = supervisor.start(
                scope, argv, workingDirectory, environment, timeout,
                request.has("stdin") && !request.isNull("stdin") ? request.getString("stdin") : null);
        watchOwner(execution);
        return new JSONObject()
                .put("ok", true)
                .put("executionId", execution.id)
                .put("state", "RUNNING")
                .toString();
    }

    private void watchOwner(RuntimeProcessSupervisor.Execution execution) {
        IBinder owner = commandOwner.get();
        if (owner != null) {
            IBinder.DeathRecipient death = execution::markCancelled;
            try {
                owner.linkToDeath(death, 0);
                execution.onFinished(() -> owner.unlinkToDeath(death, 0));
            } catch (RemoteException alreadyDead) { execution.markCancelled(); }
        }
    }

    private String awaitCommand(JSONObject request) throws JSONException {
        String projectId = request.getString("projectId");
        long generation = request.getLong("rootGeneration");
        String workspaceId = request.getString("workspaceId");
        String instanceId = request.getString("runtimeInstanceId");
        workspace.requireBound(projectId, generation, workspaceId, instanceId);
        String executionId = request.getString("executionId");
        long waitMs = request.optLong("waitMs", 30000L);

        RuntimeProcessSupervisor.BindingScope scope = new RuntimeProcessSupervisor.BindingScope(
                projectId, generation, workspaceId, instanceId);
        RuntimeProcessSupervisor.Execution execution = supervisor.getExecution(executionId, scope);
        RuntimeProcessSupervisor.Result result = null;
        if (execution != null) {
            result = supervisor.await(execution, waitMs);
        } else {
            result = supervisor.getResult(executionId, scope);
        }
        if (result == null) {
            return error("RUNTIME_EXECUTION_UNKNOWN");
        }
        boolean isTerminal = result.timedOut || result.cancelled || !result.failure.isEmpty() || result.exitCode != -1;
        return new JSONObject()
                .put("ok", result.success())
                .put("executionId", result.executionId)
                .put("state", isTerminal ? "TERMINATED" : "RUNNING")
                .put("exitCode", result.exitCode)
                .put("stdout", workspace.scrub(result.stdout))
                .put("stderr", workspace.scrub(result.stderr))
                .put("durationMs", result.durationMs)
                .put("timedOut", result.timedOut)
                .put("cancelled", result.cancelled)
                .put("cleanupConfirmed", result.cleanupConfirmed)
                .put("outputTruncated", result.outputTruncated)
                .put("failure", workspace.scrub(result.failure))
                .toString();
    }

    private String pollCommand(JSONObject request) throws JSONException {
        String projectId = request.getString("projectId");
        long generation = request.getLong("rootGeneration");
        String workspaceId = request.getString("workspaceId");
        String instanceId = request.getString("runtimeInstanceId");
        workspace.requireBound(projectId, generation, workspaceId, instanceId);
        String executionId = request.getString("executionId");

        RuntimeProcessSupervisor.BindingScope scope = new RuntimeProcessSupervisor.BindingScope(
                projectId, generation, workspaceId, instanceId);
        RuntimeProcessSupervisor.Result result = supervisor.getResult(executionId, scope);
        if (result != null) {
            return new JSONObject()
                    .put("ok", result.success())
                    .put("executionId", result.executionId)
                    .put("state", "TERMINATED")
                    .put("exitCode", result.exitCode)
                    .put("stdout", workspace.scrub(result.stdout))
                    .put("stderr", workspace.scrub(result.stderr))
                    .put("durationMs", result.durationMs)
                    .put("timedOut", result.timedOut)
                    .put("cancelled", result.cancelled)
                    .put("cleanupConfirmed", result.cleanupConfirmed)
                    .put("outputTruncated", result.outputTruncated)
                    .put("failure", workspace.scrub(result.failure))
                    .toString();
        }
        RuntimeProcessSupervisor.Execution execution = supervisor.getExecution(executionId, scope);
        if (execution == null) return error("RUNTIME_EXECUTION_UNKNOWN");
        return new JSONObject()
                .put("ok", true)
                .put("executionId", executionId)
                .put("state", "RUNNING")
                .toString();
    }

    private String cancel(JSONObject request) throws JSONException {
        String projectId = request.getString("projectId");
        long generation = request.getLong("rootGeneration");
        String workspaceId = request.getString("workspaceId");
        String instanceId = request.getString("runtimeInstanceId");
        workspace.requireBound(projectId, generation, workspaceId, instanceId);
        RuntimeProcessSupervisor.BindingScope scope = new RuntimeProcessSupervisor.BindingScope(
                projectId, generation, workspaceId, instanceId);
        String id = request.getString("executionId");
        long waitMs = request.optLong("waitMs", 2500L);
        RuntimeProcessSupervisor.Result result = supervisor.cancelAndAwait(id, scope, waitMs);
        if (result == null) return error("RUNTIME_EXECUTION_UNKNOWN");
        return new JSONObject()
                .put("ok", result.cleanupConfirmed && result.cancelled)
                .put("executionId", id)
                .put("cancelled", result.cancelled)
                .put("cleanupConfirmed", result.cleanupConfirmed)
                .toString();
    }

    private String mirrorCall(JSONObject request) throws JSONException {
        String projectId = request.getString("projectId");
        long generation = request.getLong("rootGeneration");
        String workspaceId = request.getString("workspaceId");
        String instanceId = request.getString("runtimeInstanceId");
        workspace.requireBound(projectId, generation, workspaceId, instanceId);

        String operation = request.getString("operation");
        JSONObject args = request.optJSONObject("args");
        if (args == null) args = new JSONObject();

        return workspace.handleMirrorCall(workspaceId, operation, args,
                request.optJSONObject("ignorePolicy")).toString();
    }

    private String syncBegin(JSONObject request) throws JSONException {
        requireSyncBinding(request);
        return workspace.syncBegin(request.getString("workspaceId"), request).toString();
    }

    private String syncPlanPage(JSONObject request, ParcelFileDescriptor source)
            throws JSONException {
        requireSyncBinding(request);
        if (source == null) return error("RUNTIME_SYNC_PAYLOAD_MISSING");
        byte[] payload = readBounded(source, RuntimeWorkspace.MAX_PLAN_PAGE_BYTES);
        return workspace.syncPlanPage(request.getString("workspaceId"), request, payload).toString();
    }

    private String syncFile(JSONObject request, ParcelFileDescriptor source) throws JSONException {
        requireSyncBinding(request);
        if (source == null) return error("RUNTIME_SYNC_PAYLOAD_MISSING");
        InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(source);
        return workspace.syncFile(request.getString("workspaceId"), request, stream).toString();
    }

    private String syncCommit(JSONObject request) throws JSONException {
        requireSyncBinding(request);
        return workspace.syncCommit(request.getString("workspaceId"), request).toString();
    }

    private String syncAbort(JSONObject request) throws JSONException {
        requireSyncBinding(request);
        return workspace.syncAbort(request.getString("workspaceId"), request).toString();
    }

    private void requireSyncBinding(JSONObject request) throws JSONException {
        workspace.requireBound(
                request.getString("projectId"),
                request.getLong("rootGeneration"),
                request.getString("workspaceId"),
                request.getString("runtimeInstanceId"));
    }

    /**
     * Reads a plan page with an explicit ceiling.
     *
     * A page is metadata, so it is allowed to be materialized - but only up to
     * one byte past the budget, so an oversized page is detected rather than
     * allocated. The extra byte is what distinguishes "exactly at the limit"
     * from "longer than the limit".
     */
    private static byte[] readBounded(ParcelFileDescriptor source, int limit) {
        InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(source);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] block = new byte[64 * 1024];
        try {
            int total = 0;
            while (true) {
                int read = stream.read(block);
                if (read < 0) break;
                if (read == 0) continue;
                total += read;
                if (total > limit) throw new IllegalArgumentException("RUNTIME_SYNC_PLAN_PAGE_LIMIT");
                buffer.write(block, 0, read);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PAYLOAD_INCOMPLETE", failure);
        } finally {
            closeQuietly(stream);
        }
        return buffer.toByteArray();
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private String query(JSONObject request) throws JSONException {
        String projectId = request.getString("projectId");
        RuntimeWorkspace.Binding binding = workspace.readBinding(projectId);
        return new JSONObject()
                .put("ok", true)
                .put("runtimeInstanceId", workspace.runtimeInstanceId())
                .put("activeExecutions", supervisor.activeCount())
                .put("binding", binding == null ? JSONObject.NULL : binding.toJson())
                .toString();
    }

    private String reconcile(JSONObject request) throws JSONException {
        String projectId = request.getString("projectId");
        long generation = request.getLong("rootGeneration");
        String workspaceId = request.getString("workspaceId");
        String instanceId = request.getString("runtimeInstanceId");
        RuntimeWorkspace.Binding binding =
                workspace.requireBound(projectId, generation, workspaceId, instanceId);
        File project = workspace.projectDirectory(binding.workspaceId);
        boolean ready = project.isDirectory();
        return new JSONObject()
                .put("ok", ready)
                .put("state", ready ? "READY" : "BROKEN")
                .put("runtimeInstanceId", workspace.runtimeInstanceId())
                .put("workspaceId", binding.workspaceId)
                .put("activeExecutions", supervisor.activeCount())
                .toString();
    }

    private String stop() throws JSONException {
        boolean clean = supervisor.cancelAllAndAwait(2500L);
        return new JSONObject()
                .put("ok", clean)
                .put("cleanupConfirmed", clean)
                .put("activeExecutions", supervisor.activeCount())
                .toString();
    }

    private static String error(String code) {
        try {
            return new JSONObject().put("ok", false).put("errorCode", code).toString();
        } catch (JSONException impossible) {
            return "{\"ok\":false,\"errorCode\":\"RUNTIME_RESPONSE_INVALID\"}";
        }
    }
}
