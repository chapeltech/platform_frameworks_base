package org.wonad.focusproposal

/** Proposed per-Launcher-connection command gate, called by a nonblocking Android adapter. */
class FocusCommandGate(private val clock: () -> Long, private val timeoutMillis: Long = 500L) {
    class Request internal constructor(
        val token: Long, internal val endpoint: Any, internal val epoch: Long,
        val deadlineMillis: Long, internal val route: (Int) -> Unit,
    ) {
        internal var accepting = false
        internal var callbackClaimed = false
    }
    private val lock = Any()
    private var endpoint: Any? = null
    private var epoch = 0L
    private var token = 0L
    private var pending: Request? = null

    init { require(timeoutMillis in 1L..1000L) }

    fun replace(nextEndpoint: Any?) {
        synchronized(lock) {
            check(epoch < Long.MAX_VALUE)
            epoch++
            endpoint = nextEndpoint
            pending = null
        }
    }

    /** Never evict an expired request here: a frozen UI retains its one callback/timer slot. */
    fun begin(expectedEndpoint: Any, route: (Int) -> Unit): Request? {
        synchronized(lock) {
            if (endpoint !== expectedEndpoint || pending != null || token == Long.MAX_VALUE) return null
            val now = clock()
            if (now < 0 || now > Long.MAX_VALUE - timeoutMillis) return null
            token++
            return Request(token, expectedEndpoint, epoch, now + timeoutMillis, route).also { pending = it }
        }
    }

    /** Called by the one scheduled UI timeout; early timers cannot drop a valid request. */
    fun expire(request: Request): Boolean {
        synchronized(lock) {
            if (pending !== request) return false
            val now = clock()
            if (now >= 0 && now < request.deadlineMillis) return false
            pending = null
            return true
        }
    }

    /** Dispatch/post/dead-endpoint failure drops only this request, not its replacement. */
    fun abandon(request: Request) {
        synchronized(lock) { if (pending === request) pending = null }
    }

    /** Binder listener calls BEFORE posting UI work: at most one response message per request. */
    fun claimCallback(request: Request): Boolean {
        synchronized(lock) {
            if (pending !== request || request.callbackClaimed) return false
            request.callbackClaimed = true
            val now = clock()
            // Do not free this slot from a Binder callback when UI is frozen.
            // The sole already-scheduled UI timeout must actually execute.
            return now >= 0 && now < request.deadlineMillis &&
                endpoint === request.endpoint && epoch == request.epoch
        }
    }

    /**
     * Android adapter invokes on its existing UI executor, never blocks that executor,
     * and rechecks the live TIS state + current Binder immediately before this call.
     * A malformed response belonging to this captured request is dropped, not retried.
     */
    fun accept(request: Request, currentEndpoint: Any?, payload: Map<String, Any?>?,
               eligible: (Int) -> Boolean): Boolean {
        val display: Int
        synchronized(lock) {
            if (pending !== request || request.accepting) return false
            val now = clock()
            if (endpoint !== request.endpoint || currentEndpoint !== request.endpoint ||
                epoch != request.epoch || now < 0 || now >= request.deadlineMillis ||
                payload == null || payload.keys != setOf("request_token", "display_id", "status") ||
                payload["request_token"] !is Long || payload["request_token"] != request.token ||
                payload["display_id"] !is Int || payload["status"] != "ok") {
                pending = null
                return false
            }
            display = payload["display_id"] as Int
            if (display < 0) { pending = null; return false }
            request.accepting = true
        }
        try {
            // Never call UI/task code while holding the gate's lock. Retain the
            // slot throughout validation/routing so a stalled UI cannot create
            // another queued query/response by clearing pending prematurely.
            if (!eligible(display)) return false
            synchronized(lock) {
                val now = clock()
                if (pending !== request || endpoint !== request.endpoint ||
                    currentEndpoint !== request.endpoint || epoch != request.epoch ||
                    now < 0 || now >= request.deadlineMillis) return false
            }
            request.route(display)
            return true
        } finally {
            abandon(request)
        }
    }
}
