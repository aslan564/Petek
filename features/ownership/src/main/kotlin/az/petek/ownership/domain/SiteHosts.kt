/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.domain

import java.net.URI

/** The host part of a target as ownership is judged: lower case, no trailing dot, an IPv6 literal without brackets. */
object SiteHosts {
    fun of(target: URI): String {
        require(target.scheme?.lowercase() in WEB_SCHEMES) { "Only http(s) targets have an owner to verify: $target" }
        val host =
            target.host
                ?.lowercase()
                ?.removeSurrounding("[", "]")
                ?.trimEnd('.')
        require(!host.isNullOrEmpty()) { "The target has no host: $target" }
        return host
    }

    /** An IPv4 dotted quad or an IPv6 literal (as returned by [of]); such a host has no DNS name for a TXT record. */
    fun isIpLiteral(host: String): Boolean = ':' in host || IPV4.matches(host)

    /** `localhost` and names under it never leave the machine (RFC 6761). */
    fun isLocalName(host: String): Boolean = host == LOCALHOST || host.endsWith(".$LOCALHOST")

    private const val LOCALHOST = "localhost"
    private val WEB_SCHEMES = setOf("http", "https")
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
