/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.app.config

import java.net.URI

/** Spelling rules for the web URLs Pətək is pointed at (`PETEK_TARGET`, `PETEK_MAILPIT_URL`, `--url`). */
object WebUrls {
    /**
     * [url] with a lower-case scheme and host and without the trailing dot of a fully qualified host name
     * (`HTTPS://Portal.example./app` -> `https://portal.example/app`). It is the same site, spelled the way
     * [az.petek.core.security.TargetPolicy] compares hosts, so a production host cannot pass the policy in another
     * spelling (`portal.example.` resolves to `portal.example`). Every other part is kept exactly (raw, still encoded).
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
