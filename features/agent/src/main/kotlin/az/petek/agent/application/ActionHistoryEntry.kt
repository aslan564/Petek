package az.petek.agent.application

/**
 * One earlier turn of a `do` execution as the model sees it again: what it did and what the harness observed.
 * [action] is the readable form recorded as evidence (placeholders, never secrets).
 */
data class ActionHistoryEntry(
    /** 1-based turn number within the execution. */
    val number: Int,
    val action: String,
    val observation: String,
)
