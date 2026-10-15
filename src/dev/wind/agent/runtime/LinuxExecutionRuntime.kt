package dev.wind.agent.runtime

import android.content.ComponentName
import android.content.Context

class LinuxRuntimeEndpoint(context: Context) : BinderRuntimeEndpoint(
    context = context.applicationContext,
    component = { ComponentName(context.packageName, "dev.wind.agent.runtime.linux.LinuxRuntimeService") },
    endpointId = "embedded-linux"
)

class LinuxRuntimeClient(context: Context) : CompanionRuntimeClient(
    context = context.applicationContext,
    endpointOverride = LinuxRuntimeEndpoint(context.applicationContext),
    expectedProviderId = RuntimeProviderId.VEGA_LINUX
) {
    private val hostContext = context.applicationContext
    /** Explicit host setup action; call on a worker thread, never from probe. */
    fun installBundledRuntime(): RuntimeProbeResult {
        dev.wind.agent.runtime.linux.LinuxRuntimeInstaller.installBundled(hostContext.applicationContext)
        return probe()
    }
}

class LinuxExecutionRuntime(context: Context) : CompanionExecutionRuntime(LinuxRuntimeClient(context))
