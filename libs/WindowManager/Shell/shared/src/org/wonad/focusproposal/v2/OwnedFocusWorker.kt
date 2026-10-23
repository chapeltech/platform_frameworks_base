package org.wonad.focusproposal.v2

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** false guarantees NOT accepted. Exceptions are ambiguous and must not release a held slot. */
fun interface BoundedFocusWorker { fun submit(task: Runnable): Boolean }

/** An owned executor, not a HandlerExecutor. Zero queued work; at most one live worker. */
class OwnedFocusWorker(name: String) : BoundedFocusWorker {
    private val executor = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(), { task -> Thread(task, name).also { it.isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    override fun submit(task: Runnable): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        // AbortPolicy is the exact owned implementation: thrown only before acceptance.
        false
    }

    /** Host-fixture cleanup only; production uses the process-global worker for process lifetime. */
    fun shutdownForFixture() = executor.shutdownNow()
}

/** One instance per process, never constructed per endpoint/reconnect/request/timeout. */
object ProcessFocusWorker : BoundedFocusWorker {
    private val owned = OwnedFocusWorker("WonadAuthoritativeFocus")
    override fun submit(task: Runnable) = owned.submit(task)
}
