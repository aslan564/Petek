package az.petek.llm.infrastructure.cli

import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records every [ProcessSpec] it is asked to start and hands out [FakeProcess]es built by [script].
 * It also notes whether the working directory existed (and was empty) at start, since it is deleted afterwards.
 */
class FakeProcessRunner(
    private val script: (ProcessSpec) -> FakeProcess,
) : ProcessRunner {
    val specs = CopyOnWriteArrayList<ProcessSpec>()
    val processes = CopyOnWriteArrayList<FakeProcess>()
    val workingDirectoryWasEmpty = CopyOnWriteArrayList<Boolean>()

    val lastSpec: ProcessSpec get() = specs.last()
    val lastProcess: FakeProcess get() = processes.last()

    override fun start(spec: ProcessSpec): RunningProcess {
        specs += spec
        workingDirectoryWasEmpty += Files.isDirectory(spec.workingDirectory) && isEmpty(spec.workingDirectory)
        return script(spec).also { processes += it }
    }

    private fun isEmpty(directory: Path) = Files.list(directory).use { it.findFirst().isEmpty }

    companion object {
        fun answering(
            stdout: String,
            stderr: String = "",
            exitCode: Int = 0,
        ) = FakeProcessRunner { FakeProcess(stdout, stderr, exitCode) }

        fun missingExecutable() =
            FakeProcessRunner { throw IOException("Cannot run program \"claude\": error=2, No such file or directory") }
    }
}

/** A process that prints canned output and exits, or never exits ([hangs]) until [destroyTree] is called. */
class FakeProcess(
    stdout: String = "",
    stderr: String = "",
    exitCode: Int = 0,
    val hangs: Boolean = false,
) : RunningProcess {
    private val written = ByteArrayOutputStream()
    private val exit = CompletableDeferred<Int>()

    private val destroyCalls = AtomicInteger()

    init {
        if (!hangs) exit.complete(exitCode)
    }

    val stdinText: String get() = written.toString(Charsets.UTF_8)
    val destroyCount: Int get() = destroyCalls.get()
    val destroyed: Boolean get() = destroyCount > 0

    override val stdin: OutputStream = written
    override val stdout: InputStream = ByteArrayInputStream(stdout.toByteArray())
    override val stderr: InputStream = ByteArrayInputStream(stderr.toByteArray())

    override suspend fun awaitExit(): Int = exit.await()

    override fun destroyTree() {
        destroyCalls.incrementAndGet()
        exit.complete(KILLED_EXIT_CODE)
    }

    companion object {
        const val KILLED_EXIT_CODE = 137
    }
}
