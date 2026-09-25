/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.application

import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.VerificationCode
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Polls the mailbox for the verification message of one tester (every [pollInterval], at most [timeout]),
 * extracts the code/link, marks the message read. Throws MailTimeoutException when nothing usable arrives.
 */
interface AwaitVerificationUseCase {
    suspend fun await(
        to: String,
        since: Instant,
        purpose: MailPurpose,
        timeout: Duration = 60.seconds,
        pollInterval: Duration = 1.seconds,
    ): VerificationCode

    /**
     * Like [await], for a message with a link containing a match of [pattern] (a site's own link shape, such as
     * `set-password\?token=`, where the built-in link hints would not recognise it). The default awaits a
     * [MailPurpose.LINK] message and accepts it only when its link matches; implementations that can look at every link
     * of every message override it.
     */
    suspend fun awaitLink(
        to: String,
        since: Instant,
        pattern: Regex,
        timeout: Duration = 60.seconds,
        pollInterval: Duration = 1.seconds,
    ): VerificationCode {
        val found = await(to, since, MailPurpose.LINK, timeout, pollInterval)
        return found.takeIf { it.link?.let { link -> pattern.containsMatchIn(link.toString()) } == true }
            ?: throw MailTimeoutException(to, timeout)
    }
}
