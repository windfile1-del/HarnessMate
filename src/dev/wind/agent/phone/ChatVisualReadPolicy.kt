package dev.wind.agent.phone

/** Retry only fresh reads after normal animation/content drift. Never replay a phone effect. */
internal object ChatVisualReadPolicy {
    fun transient(code: String)=code in setOf("CHAT_SCREEN_CHANGED_DURING_CAPTURE","CHAT_SCREEN_CHANGED_DURING_OCR","PHONE_WINDOW_CHANGED","VIRTUAL_DISPLAY_WINDOW_NOT_READY")
    fun <T> stable(attempt: ()->T,pause: ()->Unit): T {
        repeat(3) { index->
            try { return attempt() } catch(e: IllegalStateException) {
                if(index==2 || !transient(e.message.orEmpty().substringBefore(':'))) throw e
                pause()
            }
        }
        error("CHAT_CAPTURE_RETRIES_EXHAUSTED")
    }
}
