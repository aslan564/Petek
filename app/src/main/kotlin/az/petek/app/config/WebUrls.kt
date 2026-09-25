/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import java.net.URI

/** Spelling rules for the web URLs Pətək is pointed at (`PETEK_TARGET`, `PETEK_MAILPIT_URL`, `--url`). */
object WebUrls {
    /**
     * [url] with a lower-case scheme and host and without the trailing dot of a fully qualified host name
     * (`HTTPS://KadroHR.com./app` -> `https://kadrohr.com/app`). It is the same site, spelled the way
     * [az.petek.core.security.TargetPolicy] compares hosts, so a production host cannot pass the policy in another
     * spelling (`kadrohr.com.` resolves to `kadrohr.com`). Every other part is kept exactly (raw, still encoded).
     */
    fun canonical(url: URI): URI {
        val scheme = url.scheme ?: return url
        val host = url.host ?: return url
        val canonicalHost = host.lowercase().trimEnd('.')
        if (canonicalHost.isEmpty() || (scheme == scheme.lowercase() && host == canonicalHost)) return url
        return URI(
            buildString {
                append(scheme.lowercase()).append("://")
                url.rawUserInfo?.let { append(it).append('@') }
                append(canonicalHost)
                if (url.port != -1) append(':').append(url.port)
                append(url.rawPath.orEmpty())
                url.rawQuery?.let { append('?').append(it) }
                url.rawFragment?.let { append('#').append(it) }
            },
        )
    }
}
