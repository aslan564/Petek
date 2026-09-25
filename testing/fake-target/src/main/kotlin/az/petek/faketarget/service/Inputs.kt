/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.service

/** Normalisation and validation shared by the sign-up flows. Pure functions. */
internal object Inputs {
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_NAME_LENGTH = 100
    const val MAX_TITLE_LENGTH = 200
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val PHONE = Regex("^\\+\\d{9,15}$")
    private val PHONE_NOISE = Regex("[\\s()\\-.]")

    fun email(raw: String): String = raw.trim().lowercase()

    /** `+994 50 123-45-67` -> `+994501234567`; the plus is kept, everything else but digits is dropped. */
    fun phone(raw: String): String = raw.trim().replace(PHONE_NOISE, "")

    /** Digits only: the lookup key for `/test/otp/{phone}`, robust to `+`, `%2B` or a `+` decoded as a space. */
    fun phoneDigits(raw: String): String = raw.filter(Char::isDigit)

    fun isTestEmail(
        email: String,
        testMailDomain: String,
    ): Boolean = email.endsWith("@" + testMailDomain.trim().lowercase())

    /** Checks the fields every person enters when signing up; returns the first problem or null. */
    fun validateProfile(
        name: String,
        email: String,
        phone: String,
        password: String,
    ): Failure? =
        when {
            name.isBlank() || name.trim().length > MAX_NAME_LENGTH -> Failure.NAME_REQUIRED
            !EMAIL.matches(email) -> Failure.EMAIL_INVALID
            !PHONE.matches(phone) -> Failure.PHONE_INVALID
            password.length < MIN_PASSWORD_LENGTH -> Failure.PASSWORD_TOO_SHORT
            else -> null
        }

    fun isValidEmail(email: String): Boolean = EMAIL.matches(email)

    fun validTitle(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_LENGTH }
}
