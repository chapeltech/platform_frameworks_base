package org.wonad.focusproposal.v2

/** Server-side process-global coordinator. Owner is a fresh connection incarnation, not Binder. */
class FocusQueryCoordinator2(private val worker: BoundedFocusWorker, private val query: (Int) -> Int) {
    data class Response(val requestToken: Long, val displayId: Int, val status: String)
    private val lock = Any()
    private var owner: Any? = null
    private var inFlight = false

    fun attach(nextOwner: Any) { synchronized(lock) { owner = nextOwner } }
    fun invalidate(expectedOwner: Any) {
        synchronized(lock) { if (owner === expectedOwner) owner = null }
    }

    fun request(expectedOwner: Any, token: Long, callerUser: Int, callback: (Response) -> Unit): Boolean {
        synchronized(lock) {
            if (owner !== expectedOwner || token <= 0 || callerUser < 0 || inFlight) return false
            inFlight = true
        }
        val accepted = try {
            worker.submit(Runnable {
                try {
                    val response = try {
                        val display = query(callerUser)
                        if (display >= 0) Response(token, display, "ok")
                        else Response(token, -1, "invalid")
                    } catch (_: Exception) { Response(token, -1, "query_failed") }
                    if (synchronized(lock) { owner === expectedOwner }) callback(response)
                } finally {
                    // Query and callback IPC have really returned. Timeout/replacement never resets.
                    synchronized(lock) { inFlight = false }
                }
            })
        } catch (_: RuntimeException) {
            // A foreign/misbehaving submitter may have accepted before throwing. Retain the slot;
            // do not guess, respawn workers, or let replacement manufacture a second query.
            return false
        }
        if (!accepted) synchronized(lock) { inFlight = false }
        return accepted
    }
}
