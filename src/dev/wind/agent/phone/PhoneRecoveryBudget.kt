package dev.wind.agent.phone

/** A failed/uncertain dismissal is never automatically replayed for the same interruption. */
internal class PhoneRecoveryBudget {
    private val attempted = LinkedHashSet<String>()
    private var last = -2000L
    private var count = 0
    fun reserve(key: String, now: Long): Boolean {
        if (key in attempted || now - last < 1500 || count >= 20) return false
        attempted.add(key); last = now; count++
        return true
    }
    val attempts get() = count
}
