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

package az.petek.ownership.domain

import java.net.URI
import java.time.Instant

/**
 * The code a site's owner publishes to prove they control it: 32 lowercase hex characters (128 bits). It is not a
 * secret — it is meant to be public on the site — and it reveals nothing about the key it was derived from.
 */
@JvmInline
value class OwnershipToken(
    val value: String,
) {
    init {
        require(FORMAT.matches(value)) { "An ownership token is 32 lowercase hex characters" }
    }

    override fun toString(): String = value

    private companion object {
        val FORMAT = Regex("[0-9a-f]{32}")
    }
}

/** Where the proof was found. */
enum class OwnershipMethod(
    val key: String,
) {
    WELL_KNOWN_FILE("file"),
    DNS_TXT("dns"),
    ;

    companion object {
        fun ofKey(key: String): OwnershipMethod = entries.firstOrNull { it.key == key } ?: error("Unknown ownership method '$key'")
    }
}

/**
 * What the owner of [host] publishes, in either of two places, to let Pətək write to the site (ADR-0012):
 * - the file [fileUrl] (on the target's own scheme and port) with the line [proofLine];
 * - the DNS TXT record [dnsName] with the value [proofLine] — not for a site addressed by an IP literal ([dnsName] null).
 *
 * The host is the unit of ownership: proving `staging.example.com` does not prove `example.com` or another subdomain.
 */
data class OwnershipChallenge(
    val host: String,
    val token: OwnershipToken,
    val fileUrl: URI,
    val dnsName: String?,
) {
    val proofLine: String get() = PROOF_PREFIX + token.value

    companion object {
        const val PROOF_PREFIX = "petek-verification="
        const val FILE_PATH = "/.well-known/petek-verification.txt"
        const val DNS_LABEL = "_petek-verification"

        /** The challenge for [target], whose host is [SiteHosts.of] of it. */
        fun of(
            target: URI,
            token: OwnershipToken,
        ): OwnershipChallenge {
            val host = SiteHosts.of(target)
            val fileUrl = URI(target.scheme.lowercase(), null, host, target.port, FILE_PATH, null, null)
            val dnsName = if (SiteHosts.isIpLiteral(host)) null else "$DNS_LABEL.$host"
            return OwnershipChallenge(host, token, fileUrl, dnsName)
        }
    }
}

/** A proof that was found: [host] was verified by [method] at [verifiedAt] (harness wall time). */
data class OwnershipRecord(
    val host: String,
    val method: OwnershipMethod,
    val verifiedAt: Instant,
)
