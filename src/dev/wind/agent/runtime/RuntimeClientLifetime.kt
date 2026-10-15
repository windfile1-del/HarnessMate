package dev.wind.agent.runtime

/** Held for the host process lifetime; :runtime observes its death to stop orphan commands. */
internal object RuntimeClientLifetime {
    val binder = android.os.Binder()
}
