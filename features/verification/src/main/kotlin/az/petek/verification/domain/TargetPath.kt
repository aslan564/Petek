/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.verification.domain

import az.petek.campaign.domain.TemplateContext

/**
 * Request paths of `oracle` and `http_status` assertions, which are sent to the target (the latter with the agent's
 * cookies and any method, e.g. POST or DELETE).
 *
 * Placeholder values come from places the harness does not control: the target's page (`dom`, `url_regex`), the
 * oracle or an agent's own report. So they are percent-encoded before substitution ([encodeValues]): an id such as
 * `42/../../admin` stays inside its path segment or query value instead of changing the endpoint. The rendered path
 * must then be a plain path on the target ([problem]): it starts with a single `/` (no absolute or protocol-relative
 * URL that would bypass the target policy), has no `.`/`..` segments, backslashes, spaces or control characters.
 */
internal object TargetPath {
    /** Characters kept as they are: RFC 3986 unreserved plus `@`, which is legal in paths and queries (e-mails). */
    private const val KEPT = "-._~@"

    private const val HEX = "0123456789ABCDEF"

    private val DOT_SEGMENTS = setOf(".", "..")

    fun encodeValues(context: TemplateContext): TemplateContext =
        TemplateContext(
            lastId = context.lastId?.let(::encode),
            self = context.self.mapValues { encode(it.value) },
            eventIds = context.eventIds.mapValues { encode(it.value) },
        )

    /** Percent-encodes [value] as UTF-8 so it can only ever be one path segment or one query value. */
    fun encode(value: String): String =
        buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val code = byte.toInt() and 0xff
                val char = code.toChar()
                if (code < 0x80 && (char.isLetterOrDigit() || char in KEPT)) {
                    append(char)
                } else {
                    append('%').append(HEX[code shr 4]).append(HEX[code and 0x0f])
                }
            }
        }

    /** Why [path] must not be requested, or null when it is a plain path on the target. */
    fun problem(path: String): String? {
        val route = path.substringBefore('?').substringBefore('#')
        return when {
            !path.startsWith('/') -> "must start with '/' (absolute URLs are not allowed)"
            path.startsWith("//") -> "must not start with '//' (protocol-relative URL)"
            '\\' in path -> "must not contain '\\'"
            path.any { it.code <= 0x20 || it.code == 0x7f } -> "must not contain spaces or control characters"
            route.split('/').any { it.replace("%2e", ".", ignoreCase = true) in DOT_SEGMENTS } -> "must not contain '.' or '..' segments"
            else -> null
        }
    }
}
