/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.identity.domain

import java.text.Normalizer

/**
 * Turns a first name into the ASCII fragment used in a test e-mail's local part (`Əli` → `eli`), so the address
 * passes every mail server and form validator whatever letters the display name has.
 * Azerbaijani letters are transliterated explicitly because `ə` and `ı` have no Unicode decomposition; other
 * accented Latin letters lose their marks; any other run of characters becomes one `-`.
 */
internal object AsciiSlug {
    /** Used when nothing ASCII is left (e.g. a name written only in Cyrillic). */
    const val FALLBACK = "tester"

    /** Keeps the local part far below the 64-character limit of RFC 5321. */
    private const val MAX_LENGTH = 32

    private val AZERBAIJANI =
        mapOf(
            'ə' to 'e',
            'Ə' to 'e',
            'ı' to 'i',
            'I' to 'i',
            'İ' to 'i',
            'ö' to 'o',
            'Ö' to 'o',
            'ü' to 'u',
            'Ü' to 'u',
            'ş' to 's',
            'Ş' to 's',
            'ç' to 'c',
            'Ç' to 'c',
            'ğ' to 'g',
            'Ğ' to 'g',
        )
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val OUTSIDE_ALPHABET = Regex("[^a-z0-9]+")

    fun of(text: String): String {
        val transliterated = buildString(text.length) { text.forEach { append(AZERBAIJANI[it] ?: it) } }
        val unaccented = Normalizer.normalize(transliterated, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        val slug =
            unaccented
                .lowercase()
                .replace(OUTSIDE_ALPHABET, "-")
                .trim('-')
                .take(MAX_LENGTH)
                .trimEnd('-')
        return slug.ifEmpty { FALLBACK }
    }
}
