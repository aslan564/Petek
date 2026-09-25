package az.petek.app

import az.petek.app.cli.ExitCodes
import az.petek.app.cli.PetekCli
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/** How long Ctrl+C waits for a cancelled run to tear down its test data and write its report. */
private val SHUTDOWN_GRACE = 90.seconds

/**
 * `petek <command>`. Ctrl+C (or SIGTERM) cancels the running command and waits up to [SHUTDOWN_GRACE] for it, so a
 * run still deletes its test company and writes its report; the JVM exits once that cleanup is done.
 */
fun main(args: Array<String>) {
    // kotlin-logging announces its backend on stderr unless told not to; the CLI's stderr is for errors.
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    val shuttingDown = AtomicBoolean(false)
    val status =
        runBlocking {
            val command = async(Dispatchers.Default) { PetekCli().execute(args.toList()) }
            val hook =
                Thread({
                    shuttingDown.set(true)
                    command.cancel(CancellationException("interrupted"))
                    runBlocking { withTimeoutOrNull(SHUTDOWN_GRACE) { command.join() } }
                }, "petek-shutdown")
            Runtime.getRuntime().addShutdownHook(hook)
            try {
                command.await()
            } catch (_: CancellationException) {
                ExitCodes.INTERRUPTED
            } finally {
                if (!shuttingDown.get()) runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }
    // During JVM shutdown System.exit would block forever; the JVM ends by itself once the hooks are done.
    if (!shuttingDown.get()) exitProcess(status)
}
