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

package az.petek.mail.domain

/**
 * E-mail address rules the inboxes share. A tester's address is compared whole and case-insensitively: a `+` address
 * (`test+r1-a01@company.az`) is its own recipient, never the same as `test@company.az` or another tester's
 * `test+r1-a02@company.az`, so testers sharing the owner's box still never read each other's mail.
 */
object MailAddresses {
    private val ADDRESS = Regex("[^\\s@<>\"]+@[^\\s@<>\"]+")

    /** [address] trimmed and lower-cased; refuses anything that is not a single bare address. */
    fun normalize(address: String): String {
        val trimmed = address.trim()
        require(ADDRESS.matches(trimmed)) { "not an e-mail address: '$address'" }
        return trimmed.lowercase()
    }

    fun same(
        a: String,
        b: String,
    ): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    /** The `+` sub-address of [box] for [tag], e.g. `test@company.az` + `r1-a01` → `test+r1-a01@company.az`. */
    fun plus(
        box: String,
        tag: String,
    ): String {
        val normalized = normalize(box)
        require(TAG.matches(tag)) { "a sub-address tag is letters, digits, '.', '_' and '-', was '$tag'" }
        val local = normalized.substringBefore('@').substringBefore('+')
        return "$local+${tag.lowercase()}@${normalized.substringAfter('@')}"
    }

    private val TAG = Regex("[A-Za-z0-9._-]{1,48}")
}
