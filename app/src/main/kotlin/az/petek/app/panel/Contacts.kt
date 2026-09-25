package az.petek.app.panel

/**
 * Keeps tester contact data out of the panel's views (the PanelBackend contract): evidence texts such as oracle paths
 * and step details name testers by e-mail (`/test/tickets/latest?by=eli.a07@test.kadrohr.com`); in a view only the
 * domain stays, so the owner still sees which system was asked.
 */
internal object Contacts {
    private val EMAIL = Regex("[\\p{L}\\p{N}._%+-]+@([\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+)")

    /** [text] with every e-mail address as `***@<domain>`. */
    fun masked(text: String): String = EMAIL.replace(text) { "***@" + it.groupValues[1] }
}
