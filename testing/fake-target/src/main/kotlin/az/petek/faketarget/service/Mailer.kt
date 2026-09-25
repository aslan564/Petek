/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.service

import az.petek.faketarget.mail.MailAddress
import az.petek.faketarget.mail.MailOutbox
import az.petek.faketarget.mail.SentMail
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.strong

/**
 * The fake's e-mail templates (docs/TARGET_CONTRACT.md section 3). The verification mail contains exactly one digit run
 * (the code) and the invitation mail none besides its link, so Pətək's extractor cannot pick the wrong number.
 */
internal class Mailer(
    private val outbox: MailOutbox,
) {
    fun sendVerificationCode(
        to: MailAddress,
        code: String,
    ): SentMail =
        outbox.send(
            to = to,
            subject = "Təsdiq kodu",
            text = "Sizin təsdiq kodunuz: $code",
            html =
                createHTML().html {
                    body {
                        p {
                            +"Sizin təsdiq kodunuz: "
                            strong { +code }
                        }
                    }
                },
        )

    fun sendInvitation(
        to: MailAddress,
        companyName: String,
        link: String,
    ): SentMail {
        val greeting = if (to.name.isBlank()) "Salam!" else "Salam, ${to.name}!"
        return outbox.send(
            to = to,
            subject = "Dəvət",
            text = "$greeting\n$companyName sizi KadroHR-a dəvət edir.\nQoşulmaq üçün keçid: $link",
            html =
                createHTML().html {
                    body {
                        p { +greeting }
                        p { +"$companyName sizi KadroHR-a dəvət edir." }
                        p {
                            +"Qoşulmaq üçün keçid: "
                            a(href = link) { +link }
                        }
                    }
                },
        )
    }
}
