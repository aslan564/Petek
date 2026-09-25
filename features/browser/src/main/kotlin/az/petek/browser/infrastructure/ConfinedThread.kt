package az.petek.browser.infrastructure

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One dedicated thread that owns a Playwright instance and everything created from it (CLAUDE.md rule 9:
 * Playwright Java is not thread-safe, and its objects must be used from the thread that created them).
 * Callers on any coroutine hop onto the thread with [run]; blocks execute one at a time in submission order.
 *
 * A cancelled caller stops waiting, but a Playwright call that already started finishes on the thread (Playwright
 * calls are not interruptible without corrupting its connection); its own timeout bounds how long that takes.
 * The thread is a daemon so a forgotten session never keeps the JVM alive.
 */
internal class ConfinedThread(
    val name: String,
) : AutoCloseable {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name).apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()

    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }

    /** Like [run], but completes even when the caller is cancelled; used for releasing resources. */
    suspend fun <T> runToCompletion(block: () -> T): T = withContext(NonCancellable + dispatcher) { block() }

    /**
     * Lets queued blocks finish and ends the thread, waiting briefly for it so that a closed session leaves no
     * thread behind. Blocks submitted afterwards are rejected. Must not be called from the thread itself.
     */
    override fun close() {
        executor.shutdown()
        executor.awaitTermination(TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val TERMINATION_WAIT_SECONDS = 5L
    }
}
