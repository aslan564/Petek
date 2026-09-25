package az.petek.app.cli

/** Process exit codes of `petek`, stable for scripts and CI. */
object ExitCodes {
    /** Success; for `run`: the run PASSED. */
    const val OK = 0

    /** A check or run found problems (run FAILED, invalid campaign for `plan`, doctor/probe not green). */
    const val FAILURE = 1

    /** Nothing could be done: wrong command line, invalid configuration, refused target, or a run that was ABORTED. */
    const val CONFIG_OR_ABORTED = 2

    /** Interrupted (Ctrl+C); the run was still torn down and reported. */
    const val INTERRUPTED = 130
}
