/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.agent.domain

import az.petek.identity.domain.Identity

/**
 * Where the test team's mail goes (Faza 16): the catch-all [domain] every generated address is at, or the owner's
 * [mailbox], whose `+` sub-addresses the testers get instead. Only one of them is set: with a mailbox the domain is not
 * used for testers, and the rest of the mailbox's own domain may belong to real people.
 */
data class TestMail(
    val domain: String?,
    val mailbox: String?,
) {
    companion object {
        /** No shared address space: only the testers' own addresses belong to the team. */
        val NONE = TestMail(null, null)

        /** The address space testers get addresses in: the mailbox's `+` addresses when there is one, else the domain. */
        fun of(
            domain: String?,
            mailbox: String?,
        ): TestMail = if (mailbox.isNullOrBlank()) TestMail(domain?.takeIf { it.isNotBlank() }, null) else TestMail(null, mailbox)
    }
}

/**
 * The e-mail addresses and phone numbers a tester may type (Faza 24.3): its own, its colleagues' (the run's roster),
 * and the test team's address space ([TestMail]). Anything else shaped like an e-mail address or an international
 * phone number (`+` and digits) is refused before it reaches the page, so a task such as "invite a colleague" can
 * never make the site under test write to or text a real third party. Placeholders (`{self.email}`) are the harness's
 * own values and are not looked at.
 */
class ContactPolicy(
    own: Identity,
    colleagues: List<Colleague>,
    private val mail: TestMail,
) {
    private val known: Set<String> = (colleagues.map { it.email } + own.email).mapTo(HashSet()) { it.lowercase() }
    private val ownPhone: String = digits(own.phone)

    /** Why [text] must not be typed, or null when every e-mail address and phone number in it belongs to the test team. */
    fun refusal(text: String): String? {
        val literal = PLACEHOLDER.replace(text, " ")
        val strangers =
            EMAIL
                .findAll(literal)
                .map { it.value }
                .filterNot(::isTeamAddress)
                .toList() +
                PHONE
                    .findAll(literal)
                    .map { it.value }
                    .filterNot { digits(it) == ownPhone }
                    .toList()
        if (strangers.isEmpty()) return null
        val space = mail.domain?.let { ", any address at @$it" } ?: mail.mailbox?.let { ", a + address of the test inbox" }.orEmpty()
        return "Only e-mail addresses and phone numbers of the test team can be typed, never a real person's " +
            "(not the test team's: ${strangers.distinct().joinToString { "'$it'" }}). " +
            "Use {self.email} or {self.phone}, an address your task names$space."
    }

    private fun isTeamAddress(address: String): Boolean {
        val normalized = address.lowercase()
        if (normalized in known) return true
        val local = normalized.substringBefore('@')
        val domain = normalized.substringAfter('@')
        if (mail.domain != null && domain == mail.domain.lowercase()) return true
        val box = mail.mailbox?.lowercase() ?: return false
        val boxLocal = box.substringBefore('@')
        return domain == box.substringAfter('@') && (local == boxLocal || local.startsWith("$boxLocal+"))
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{[A-Za-z_][A-Za-z0-9_.]*}")
        val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
        val PHONE = Regex("\\+\\d[\\d ()./-]{5,}\\d")

        fun digits(text: String): String = text.filter(Char::isDigit)
    }
}
