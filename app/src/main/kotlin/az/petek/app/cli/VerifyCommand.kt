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

package az.petek.app.cli

import az.petek.app.config.PetekConfig
import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipStatus
import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/**
 * `petek verify [--url <url>]`: shows what the owner of the site under test publishes so that Pətək may write to it
 * (ADR-0012) and looks for it now: the file `/.well-known/petek-verification.txt` or the DNS TXT record
 * `_petek-verification.<host>`, each carrying `petek-verification=<token>`. A proof found is remembered. Exit code 0
 * when full tests may run (proved, or a loopback or private-network address), 1 when the proof is not there.
 */
class VerifyCommand : PetekSubcommand("verify") {
    private val url by urlOption("site to verify (default: PETEK_TARGET)")

    override fun help(context: Context): String =
        "Show and check the proof that the site is yours; Pətək writes (runs, sign-up) only to a proved or local site."

    override suspend fun execute(): Int =
        withContainer { container ->
            val config = container.config
            val target = url ?: config.target
            TargetGuard.requireAllowed(config.targetPolicy, target)
            val status = container.ownership.verify(target)
            val challenge = container.ownership.challenge(target)
            if (json) emitJson(document(status, challenge)) else print(target, status, challenge)
            if (status.allowsWrites) ExitCodes.OK else ExitCodes.FAILURE
        }

    private fun print(
        target: URI,
        status: OwnershipStatus,
        challenge: OwnershipChallenge,
    ) {
        echo("Site: ${PetekConfig.masked(target)} (host ${status.host})")
        when (status) {
            is OwnershipStatus.Exempt -> {
                echo("Ownership: no proof needed, the host is a loopback or private-network address. Full tests may run.")
            }

            is OwnershipStatus.Verified -> {
                echo("Ownership: proved by the ${status.record.method.key} on ${status.record.verifiedAt}. Full tests may run.")
            }

            is OwnershipStatus.Unverified -> {
                echo("Ownership: not proved. Pətək only reads this site until its owner publishes one of:")
                echo("  1. the file ${challenge.fileUrl} with the line:")
                echo("       ${challenge.proofLine}")
                challenge.dnsName?.let {
                    echo("  2. the DNS TXT record $it with the value:")
                    echo("       ${challenge.proofLine}")
                }
                if (status.looked.isNotEmpty()) {
                    echo("Looked for it:")
                    status.looked.forEach { echo("  - $it") }
                }
                echo("Publish either, then run petek verify again or simply start the run.")
            }
        }
    }

    private fun document(
        status: OwnershipStatus,
        challenge: OwnershipChallenge,
    ) = buildJsonObject {
        put("host", status.host)
        put(
            "status",
            when (status) {
                is OwnershipStatus.Exempt -> "exempt"
                is OwnershipStatus.Verified -> "verified"
                is OwnershipStatus.Unverified -> "unverified"
            },
        )
        put("allowsWrites", status.allowsWrites)
        if (status is OwnershipStatus.Verified) {
            put("method", status.record.method.key)
            put("verifiedAt", status.record.verifiedAt.toString())
        }
        putJsonObject("proof") {
            put("line", challenge.proofLine)
            put("file", challenge.fileUrl.toString())
            challenge.dnsName?.let { put("dnsName", it) }
        }
        val looked = (status as? OwnershipStatus.Unverified)?.looked.orEmpty()
        putJsonArray("looked") { looked.forEach { add(it) } }
    }
}
