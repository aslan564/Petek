/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.service

import java.security.SecureRandom

/** Random codes and tokens. Tests never predict them; they read codes through Mailpit or `/test/otp`. */
internal class SecretGenerator(
    val random: SecureRandom = SecureRandom(),
) {
    /** Six digits without a leading zero, e.g. `482913`. */
    fun sixDigitCode(): String = (100_000 + random.nextInt(900_000)).toString()

    /** Four digits for company codes (`PTK-4821`). */
    fun fourDigits(): String = (1_000 + random.nextInt(9_000)).toString()

    /** Letters only, so an invitation mail never contains a digit run that looks like a verification code. */
    fun letterToken(length: Int = 32): String = random(LETTERS, length)

    fun sessionToken(): String = random(ALPHANUMERIC, 43)

    /** Mailpit-style message id. */
    fun mailId(): String = random(ALPHANUMERIC, 22)

    private fun random(
        alphabet: String,
        length: Int,
    ): String = buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    private companion object {
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        const val ALPHANUMERIC = LETTERS + "0123456789"
    }
}
