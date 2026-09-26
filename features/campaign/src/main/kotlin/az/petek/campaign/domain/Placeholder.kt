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

package az.petek.campaign.domain

/**
 * A `{name}` template placeholder, classified. Shared by [TemplateRenderer] (to resolve it) and [CampaignValidator]
 * (to reject names a campaign may not use before anything runs).
 */
sealed interface Placeholder {
    val name: String

    /** `{last_id}`: id of the most recently emitted object, as supplied in [TemplateContext.lastId]. */
    data object LastId : Placeholder {
        override val name: String = LAST_ID
    }

    /** `{self.<field>}`: a field of the acting tester's identity, read from [TemplateContext.self]. */
    data class Self(
        val field: String,
    ) : Placeholder {
        override val name: String = "self.$field"
    }

    /** `{event.<event>.id}`: id of the object carried by the latest event named [event]. */
    data class EventId(
        val event: String,
    ) : Placeholder {
        override val name: String = "event.$event.id"
    }

    /**
     * `{tester.<role>.<n>.<field>}`: a field of the [index]-th tester (1-based, agent order) of [role], e.g.
     * `{tester.manager.1.email}` for the invitation a card must send (Faza 18). Testers never learn about each other
     * from their prompt; a card that needs another tester's name or e-mail names it this way, and the harness writes
     * the value in at the last moment without saying whose it is. Only [TESTER_FIELDS], never secrets.
     */
    data class Tester(
        val role: String,
        val index: Int,
        val field: String,
    ) : Placeholder {
        override val name: String = "tester.$role.$index.$field"

        /** The key of the tester in [TemplateContext.testers]: `<role>.<n>`. */
        val key: String get() = "$role.$index"
    }

    companion object {
        /** A placeholder is `{` + a name matching this pattern + `}`; any other brace text is literal. */
        val NAME_PATTERN: Regex = Regex("[a-z_][a-z0-9_.]*")

        /**
         * `self` fields campaign files may use. The password is absent on purpose: secrets reach the page only when the
         * harness types them (CLAUDE.md rule 10), never through rendered prompts, URLs or assertions.
         */
        val CAMPAIGN_SELF_FIELDS: Set<String> = linkedSetOf("email", "name", "agent_id", "department", "role", "phone")

        /**
         * `self` fields flows may use (see [Flow]): the campaign's, the display name split for sign-up forms that ask
         * for first and last name separately, and the password, which only the harness types (flows never reach the LLM).
         */
        val FLOW_SELF_FIELDS: Set<String> = CAMPAIGN_SELF_FIELDS + linkedSetOf("first_name", "last_name", "password")

        /** `{api}`: [TargetProfile.apiPrefix], replaced in campaign paths when the file is loaded (see [expandApiPrefix]). */
        const val API_PREFIX: String = "{api}"

        /** What a card may say about another tester: how to address them, nothing more. */
        val TESTER_FIELDS: Set<String> = linkedSetOf("name", "email")

        private const val LAST_ID = "last_id"
        private const val TESTER_PREFIX = "tester."
        private const val SELF_PREFIX = "self."
        private const val EVENT_PREFIX = "event."
        private const val EVENT_SUFFIX = ".id"

        /** Classifies a placeholder name (without braces); null when it is none of the supported forms. */
        fun parse(name: String): Placeholder? =
            when {
                name == LAST_ID -> {
                    LastId
                }

                name.startsWith(SELF_PREFIX) && name.length > SELF_PREFIX.length -> {
                    Self(name.removePrefix(SELF_PREFIX))
                }

                name.startsWith(TESTER_PREFIX) -> {
                    val parts = name.removePrefix(TESTER_PREFIX).split('.')
                    val index = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size == 3 && parts[0].isNotEmpty() && index != null && index >= 1 && parts[2].isNotEmpty()) {
                        Tester(parts[0], index, parts[2])
                    } else {
                        null
                    }
                }

                name.startsWith(EVENT_PREFIX) &&
                    name.endsWith(EVENT_SUFFIX) &&
                    name.length > EVENT_PREFIX.length + EVENT_SUFFIX.length -> {
                    EventId(name.substring(EVENT_PREFIX.length, name.length - EVENT_SUFFIX.length))
                }

                else -> {
                    null
                }
            }

        /** Human-readable list of the forms a campaign may use, for error messages. */
        val SUPPORTED_FORMS: String =
            (
                listOf("{$LAST_ID}") + CAMPAIGN_SELF_FIELDS.map { "{$SELF_PREFIX$it}" } + "{event.<event>.id}" +
                    "{tester.<role>.<n>.name|email}"
            ).joinToString(", ")
    }
}
