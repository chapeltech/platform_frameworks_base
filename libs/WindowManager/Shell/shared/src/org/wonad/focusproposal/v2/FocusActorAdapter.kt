package org.wonad.focusproposal.v2

import org.wonad.focusproposal.FocusCommandGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Android implementation uses Handler(Looper.getMainLooper()), with checked Boolean posts. */
interface FocusMainScheduler {
    fun assertMain()
    fun post(task: Runnable): Boolean
    fun postDelayed(task: Runnable, delayMillis: Long): Boolean
    fun remove(task: Runnable)
}

/**
 * All gate, connection, eligibility and command admission effects are MAIN-serialized.
 * Binder listeners only claim bounded ingress/reply slots and post/remove exact Runnables.
 */
class FocusActorAdapter(
    private val main: FocusMainScheduler,
    private val worker: BoundedFocusWorker,
    private val clock: () -> Long,
    private val eligible: (Int) -> Boolean,
) {
    class Connection(val binderIdentity: Any, val connectionEpoch: Long,
        val send: (Long, (Map<String, Any?>?) -> Unit) -> Unit)

    private class Ticket(val connection: Connection, val deadline: Long,
        val route: (Int) -> Unit) {
        val retired = AtomicBoolean(false)
        val replyClaimed = AtomicBoolean(false)
        val replyTask = AtomicReference<Runnable?>(null)
        lateinit var ingress: Runnable
        lateinit var timer: Runnable
        var request: FocusCommandGate.Request? = null // MAIN only
    }

    private val gate = FocusCommandGate(clock)
    private val slot = AtomicReference<Ticket?>(null)
    private val destroyed = AtomicBoolean(false)
    @Volatile private var connection: Connection? = null

    /** Every initialization/reconnection gets a NEW Connection, even for the same raw Binder. */
    fun replace(next: Connection?) {
        main.assertMain()
        if (destroyed.get()) return
        retire(slot.getAndSet(null))
        connection = next
        gate.replace(next)
    }

    fun destroy() {
        main.assertMain()
        destroyed.set(true)
        retire(slot.getAndSet(null))
        connection = null
        gate.replace(null)
    }

    /** Nonblocking Binder ingress: frozen MAIN retains ONE ticket, never one post per keypress. */
    fun submit(route: (Int) -> Unit): Boolean {
        if (destroyed.get()) return false
        val captured = connection ?: return false
        val now = clock()
        if (now < 0 || now > Long.MAX_VALUE - 500L) return false
        val ticket = Ticket(captured, now + 500L, route)
        ticket.ingress = Runnable { beginOnMain(ticket) }
        ticket.timer = Runnable { timeoutOnMain(ticket) }
        if (!slot.compareAndSet(null, ticket)) return false
        if (destroyed.get() || connection !== captured) {
            ticket.retired.set(true)
            slot.compareAndSet(ticket, null)
            return false
        }
        if (!main.post(ticket.ingress)) {
            ticket.retired.set(true)
            slot.compareAndSet(ticket, null) // no gate request exists if posting was not accepted
            return false
        }
        if (ticket.retired.get()) main.remove(ticket.ingress)
        return true
    }

    private fun live(ticket: Ticket): Boolean {
        val now = clock()
        return !ticket.retired.get() && !destroyed.get() && slot.get() === ticket &&
            connection === ticket.connection && now >= 0 && now < ticket.deadline
    }

    private fun beginOnMain(ticket: Ticket) {
        main.assertMain()
        if (!live(ticket)) { finishOnMain(ticket); return }
        val request = gate.begin(ticket.connection) { display ->
            // Actual call is synchronous MAIN command admission, not an extra UI-executor post.
            if (live(ticket)) ticket.route(display)
        } ?: run { finishOnMain(ticket); return }
        ticket.request = request
        if (!main.postDelayed(ticket.timer, ticket.deadline - clock())) {
            finishOnMain(ticket); return
        }
        val accepted = try {
            worker.submit(Runnable {
                try { ticket.connection.send(request.token) { reply(ticket, it) } }
                catch (_: Exception) { reply(ticket, null) }
            })
        } catch (_: RuntimeException) { false }
        if (!accepted) finishOnMain(ticket)
    }

    /** Callback Binder thread: no Gate mutation, no UI work, at most ONE reply post. */
    private fun reply(ticket: Ticket, payload: Map<String, Any?>?) {
        if (!live(ticket) || !ticket.replyClaimed.compareAndSet(false, true)) return
        val task = Runnable {
            main.assertMain()
            try {
                if (live(ticket)) ticket.request?.let { request ->
                    gate.accept(request, connection, payload) { display ->
                        live(ticket) && eligible(display)
                    }
                }
            } finally { finishOnMain(ticket) }
        }
        ticket.replyTask.set(task)
        if (ticket.retired.get()) return
        if (!main.post(task)) {
            // Keep the one UI/timer slot until its already accepted timeout really executes.
            ticket.retired.set(true)
            return
        }
        // Replacement can interleave before/after post. Remove this EXACT retired callback.
        if (ticket.retired.get()) main.remove(task)
    }

    private fun timeoutOnMain(ticket: Ticket) {
        main.assertMain()
        if (slot.get() !== ticket) return
        val now = clock()
        if (now >= 0 && now < ticket.deadline && !ticket.retired.get()) {
            if (!main.postDelayed(ticket.timer, ticket.deadline - now)) finishOnMain(ticket)
        } else finishOnMain(ticket)
    }

    private fun finishOnMain(ticket: Ticket) {
        main.assertMain()
        ticket.request?.let(gate::abandon)
        slot.compareAndSet(ticket, null)
        retire(ticket)
    }

    private fun retire(ticket: Ticket?) {
        if (ticket == null) return
        ticket.retired.set(true)
        main.remove(ticket.ingress)
        main.remove(ticket.timer)
        ticket.replyTask.get()?.let(main::remove)
    }
}
