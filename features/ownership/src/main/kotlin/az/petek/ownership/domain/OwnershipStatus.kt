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

/** Whether Pətək may write to a site (run, sign up, touch) or only read it. */
sealed interface OwnershipStatus {
    val host: String

    /** True when a full test may run: the site is local, or its owner's proof is known. */
    val allowsWrites: Boolean get() = this !is Unverified

    /** Loopback, `localhost` or a private network: nobody else's public site, no proof needed. */
    data class Exempt(
        override val host: String,
    ) : OwnershipStatus

    /** The owner's proof was found, now or within the re-check period. */
    data class Verified(
        val record: OwnershipRecord,
    ) : OwnershipStatus {
        override val host: String get() = record.host
    }

    /** No proof: [challenge] says what to publish, [looked] what was looked at and what was there instead. */
    data class Unverified(
        val challenge: OwnershipChallenge,
        val looked: List<String>,
    ) : OwnershipStatus {
        override val host: String get() = challenge.host
    }
}

/** One look for the proof. */
sealed interface ProofLook {
    data class Found(
        val method: OwnershipMethod,
    ) : ProofLook

    /** Not found; each entry names a place and what was there instead, e.g. `https://…/petek-verification.txt: HTTP 404`. */
    data class Missing(
        val looked: List<String>,
    ) : ProofLook
}
