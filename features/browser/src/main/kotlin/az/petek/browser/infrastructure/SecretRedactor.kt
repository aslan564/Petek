package az.petek.browser.infrastructure

import az.petek.browser.domain.PageSnapshot

/**
 * Removes the current values of a page's secret fields from text captured from that page (CLAUDE.md rule 10).
 * Needed because Playwright's ARIA snapshot prints every textbox value, password fields included
 * (`- textbox "Şifrə": hunter2`).
 *
 * Two passes: textbox lines of an ARIA snapshot whose value *is* a secret get the value masked, however short;
 * elsewhere, every occurrence of a secret of at least [MIN_FREE_TEXT_LENGTH] characters is masked. The length floor
 * keeps a one- or two-character value from shredding unrelated text.
 */
internal object SecretRedactor {
    const val MASK = "******"
    const val MIN_FREE_TEXT_LENGTH = 4

    private val textboxLine = Regex("""^(\s*- textbox(?: "(?:[^"\\]|\\.)*")?(?: \[[^\]]*])*: )(.+)$""")

    fun redactAriaSnapshot(
        yaml: String,
        secrets: Collection<String>,
    ): String {
        val wanted = secrets.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return yaml
        val maskedValues =
            yaml.lines().joinToString("\n") { line ->
                val match = textboxLine.matchEntire(line) ?: return@joinToString line
                val (prefix, value) = match.destructured
                if (unquoted(value) in wanted) prefix + MASK else line
            }
        return redactText(maskedValues, wanted)
    }

    /**
     * Masks [secrets] in everything a snapshot shows the agent: an element value that *is* a secret, however short,
     * and longer secrets anywhere in names, values, the title, the URL and the visible text.
     */
    fun redactSnapshot(
        snapshot: PageSnapshot,
        secrets: Collection<String>,
    ): PageSnapshot {
        val wanted = secrets.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return snapshot
        return snapshot.copy(
            url = redactText(snapshot.url, wanted),
            title = redactText(snapshot.title, wanted),
            elements =
                snapshot.elements.map { element ->
                    element.copy(
                        name = redactText(element.name, wanted),
                        value = element.value?.let { value -> if (value in wanted) MASK else redactText(value, wanted) },
                    )
                },
            visibleText = redactText(snapshot.visibleText, wanted),
        )
    }

    fun redactText(
        text: String,
        secrets: Collection<String>,
    ): String =
        secrets
            .filter { it.length >= MIN_FREE_TEXT_LENGTH }
            .sortedByDescending { it.length }
            .fold(text) { redacted, secret -> redacted.replace(secret, MASK) }

    /** YAML double-quoted scalars escape `"` and `\`; plain scalars are taken as they are. */
    private fun unquoted(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            value
        }
}
