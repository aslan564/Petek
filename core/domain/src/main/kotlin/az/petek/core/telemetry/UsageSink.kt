/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.core.telemetry

/**
 * The telemetry port (ADR-0011): opt-in, off by default, counters only. A counter has a fixed [name] such as
 * `llm.calls` and at most a few [tags] whose values are short machine words (a provider key, a bucket), never
 * content: no URL, text, name, e-mail or secret ever reaches a sink. [Tags.of] enforces that.
 *
 * The open core ships [NONE] and a local file the owner can read; a hosted edition may send the same counters to an
 * account.
 */
fun interface UsageSink {
    fun count(
        name: String,
        amount: Long,
        tags: Tags,
    )

    /** Counter tags, checked: keys and values are 1–40 characters of `a-z 0-9 . _ -`. */
    @JvmInline
    value class Tags private constructor(
        val values: Map<String, String>,
    ) {
        companion object {
            val NONE = Tags(emptyMap())

            fun of(vararg pairs: Pair<String, String>): Tags {
                pairs.forEach { (key, value) ->
                    require(WORD.matches(key) && WORD.matches(value)) { "telemetry tags carry machine words only, got '$key'" }
                }
                return Tags(sortedMapOf(*pairs))
            }

            private val WORD = Regex("[a-z0-9._-]{1,40}")
        }
    }

    companion object {
        /** Telemetry off (the default): nothing is counted anywhere. */
        val NONE: UsageSink = UsageSink { _, _, _ -> }

        private val NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*")

        /** Counter names are dotted machine words, e.g. `llm.calls`. */
        fun requireName(name: String) = require(NAME.matches(name) && name.length <= 64) { "invalid telemetry counter '$name'" }
    }
}
