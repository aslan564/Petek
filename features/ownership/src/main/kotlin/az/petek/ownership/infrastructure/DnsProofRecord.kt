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

package az.petek.ownership.infrastructure

import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipProbe
import az.petek.ownership.domain.ProofLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Hashtable
import javax.naming.Context
import javax.naming.NameNotFoundException
import javax.naming.directory.InitialDirContext

/** The TXT strings of one DNS name, each already joined and unquoted; an empty list when the name has none. */
fun interface TxtLookup {
    /** Blocking; called on [Dispatchers.IO]. */
    fun txt(name: String): List<String>
}

/**
 * Looks for the TXT record `_petek-verification.<host>` whose value is [OwnershipChallenge.proofLine]; a name may carry
 * several TXT records (one per machine token). A site addressed by an IP literal has no name to look under.
 */
class DnsProofRecord(
    private val lookup: TxtLookup = JndiTxtLookup(),
) : OwnershipProbe {
    override suspend fun look(
        target: URI,
        challenge: OwnershipChallenge,
    ): ProofLook {
        val name =
            challenge.dnsName ?: return ProofLook.Missing(listOf("DNS: the site is addressed by an IP, which has no name for a TXT record"))
        val values =
            try {
                withContext(Dispatchers.IO) { lookup.txt(name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return missing(name, e.message?.takeIf(String::isNotBlank) ?: e::class.simpleName ?: "lookup failed")
            }
        return when {
            values.any { it.trim() == challenge.proofLine } -> ProofLook.Found(OwnershipMethod.DNS_TXT)
            values.isEmpty() -> missing(name, "no TXT record")
            else -> missing(name, "no TXT value ${challenge.proofLine}")
        }
    }

    private fun missing(
        name: String,
        what: String,
    ) = ProofLook.Missing(listOf("DNS TXT $name: $what"))
}

/**
 * [TxtLookup] through the JDK's JNDI DNS provider (`jdk.naming.dns`, part of the bundle's runtime image), with the
 * system's resolvers and a short timeout. A name that does not exist is an empty list, not an error.
 */
class JndiTxtLookup(
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val retries: Int = DEFAULT_RETRIES,
) : TxtLookup {
    override fun txt(name: String): List<String> {
        val environment =
            Hashtable<String, String>().apply {
                put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
                put("com.sun.jndi.dns.timeout.initial", timeoutMillis.toString())
                put("com.sun.jndi.dns.timeout.retries", retries.toString())
            }
        val context = InitialDirContext(environment)
        return try {
            val attribute = context.getAttributes("dns:/$name", arrayOf("TXT")).get("TXT") ?: return emptyList()
            (0 until attribute.size()).map { TxtValues.join(attribute.get(it).toString()) }
        } catch (_: NameNotFoundException) {
            emptyList()
        } finally {
            context.close()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3000
        const val DEFAULT_RETRIES = 1
    }
}

/** A TXT record as JNDI prints it — `"part one" "part two"`, or bare text — as the one string the owner published. */
object TxtValues {
    fun join(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith('"')) return text
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        for (char in text) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }

                char == '\\' && quoted -> {
                    escaped = true
                }

                char == '"' -> {
                    if (quoted) parts += current.toString().also { current.clear() }
                    quoted = !quoted
                }

                quoted -> {
                    current.append(char)
                }
            }
        }
        if (quoted) parts += current.toString()
        return parts.joinToString("")
    }
}
