/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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

        private const val LAST_ID = "last_id"
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
            (listOf("{$LAST_ID}") + CAMPAIGN_SELF_FIELDS.map { "{$SELF_PREFIX$it}" } + "{event.<event>.id}")
                .joinToString(", ")
    }
}
