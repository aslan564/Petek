/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.infrastructure

import az.petek.core.security.Secret
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The owner's own inbox over IMAP (`PETEK_MAIL_SOURCE=imap`): a catch-all domain or a box whose `+` addresses reach
 * it (`test+r1-a01@company.az` lands in `test@company.az`). The password travels as a [Secret] and is never logged.
 */
data class ImapSettings(
    val host: String,
    val username: String,
    val password: Secret,
    val port: Int = DEFAULT_TLS_PORT,
    val tls: Boolean = true,
    val folder: String = DEFAULT_FOLDER,
    val timeout: Duration = 15.seconds,
) {
    init {
        require(host.isNotBlank()) { "IMAP host must not be blank" }
        require(username.isNotBlank()) { "IMAP username must not be blank" }
        require(!password.isBlank) { "IMAP password must not be blank" }
        require(port in 1..MAX_PORT) { "IMAP port must be 1-$MAX_PORT, was $port" }
        require(folder.isNotBlank()) { "IMAP folder must not be blank" }
        require(timeout.isPositive()) { "IMAP timeout must be positive" }
    }

    override fun toString(): String = "ImapSettings($username@$host:$port, tls=$tls, folder=$folder)"

    companion object {
        const val DEFAULT_TLS_PORT = 993
        const val DEFAULT_PLAIN_PORT = 143
        const val DEFAULT_FOLDER = "INBOX"
        private const val MAX_PORT = 65_535
    }
}
