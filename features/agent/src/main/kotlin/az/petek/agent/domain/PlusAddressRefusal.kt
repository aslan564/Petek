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

/**
 * Recognises a site that refuses `+` e-mail addresses (Faza 16): the tester registered with the owner's
 * `test+r1-a01@company.az` and the page answers with an e-mail validation error. The report then says so plainly and
 * names the alternative (a catch-all domain), instead of a bare "registration failed".
 */
object PlusAddressRefusal {
    const val HINT: String =
        "The site seems to refuse '+' e-mail addresses: it rejected the tester's sub-address of the owner's inbox. " +
            "Use a catch-all domain (PETEK_MAIL_DOMAIN with every address delivered to one box) instead of PETEK_MAIL_INBOX."

    /** True when [email] is a `+` address and [pageText] shows an e-mail validation error. */
    fun detect(
        email: String,
        pageText: String,
    ): Boolean {
        if ('+' !in email.substringBefore('@')) return false
        val text = pageText.lowercase()
        return EMAIL_WORDS.any { it in text } && REFUSAL_WORDS.any { it in text }
    }

    private val EMAIL_WORDS = listOf("email", "e-mail", "e-poçt", "epoçt", "poçt", "почт", "posta", "correo")
    private val REFUSAL_WORDS =
        listOf(
            "invalid",
            "not valid",
            "valid email",
            "valid e-mail",
            "not allowed",
            "unsupported",
            "yanlış",
            "düzgün deyil",
            "etibarsız",
            "keçərsiz",
            "некоррект",
            "недопустим",
            "неверн",
            "geçersiz",
            "no válido",
        )
}
