/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
