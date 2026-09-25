package az.petek.browser.infrastructure

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One dedicated thread that owns a Playwright instance and everything created from it (CLAUDE.md rule 9:
 * Playwright Java is not thread-safe, and its objects must be used from the thread that created them).
 * Callers on any coroutine hand blocks to the thread with [run]; blocks execute one at a time in submission order.
 *
 * A cancelled caller stops waiting at once, so a step timeout or the watchdog is never held up by a browser call.
 * A block that has not started yet is then skipped; one that already started finishes on the thread (Playwright
 * calls are not interruptible without corrupting its connection), and its own timeout bounds how long that takes.
 * The thread is a daemon so a forgotten session never keeps the JVM alive.
 */
internal class ConfinedThread(
    val name: String,
) : AutoCloseable {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name).apply { isDaemon = true } }

    /** Runs [block] on the thread. Throws [RejectedExecutionException] once the thread is [close]d. */
    suspend fun <T> run(block: () -> T): T = submit(block).await()

    /** Like [run], but the caller keeps waiting even when cancelled; used for releasing resources. */
    suspend fun <T> runToCompletion(block: () -> T): T {
        val result = submit(block)
        return withContext(NonCancellable) { result.await() }
    }

    /**
     * Lets queued blocks finish and ends the thread, waiting briefly for it so that a closed session leaves no
     * thread behind. Blocks submitted afterwards are rejected. Blocking; must not be called from the thread itself.
     */
    override fun close() {
        executor.shutdown()
        executor.awaitTermination(TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun <T> submit(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, executor)

    private companion object {
        const val TERMINATION_WAIT_SECONDS = 5L
    }
}
