package az.petek.llm.infrastructure.cli

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
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
        return script(spec).also {
            it.run(spec)
            processes += it
        }
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

/**
 * A process that reads its STDIN file, writes canned output to its STDOUT/STDERR files and exits, or never exits
 * ([hangs]) until [destroyTree] is called.
 */
class FakeProcess(
    private val stdout: String = "",
    private val stderr: String = "",
    exitCode: Int = 0,
    val hangs: Boolean = false,
) : RunningProcess {
    private val exit = CompletableDeferred<Int>()
    private val destroyCalls = AtomicInteger()

    init {
        if (!hangs) exit.complete(exitCode)
    }

    /** Everything the CLI would have read from STDIN. */
    @Volatile
    var stdinText: String = ""
        private set

    val destroyCount: Int get() = destroyCalls.get()
    val destroyed: Boolean get() = destroyCount > 0

    /** What a real process does with its redirected streams, done at start. */
    fun run(spec: ProcessSpec) {
        stdinText = Files.readString(spec.stdinFile)
        Files.writeString(spec.stdoutFile, stdout)
        Files.writeString(spec.stderrFile, stderr)
    }

    override suspend fun awaitExit(): Int = exit.await()

    override fun destroyTree() {
        destroyCalls.incrementAndGet()
        exit.complete(KILLED_EXIT_CODE)
    }

    companion object {
        const val KILLED_EXIT_CODE = 137
    }
}
