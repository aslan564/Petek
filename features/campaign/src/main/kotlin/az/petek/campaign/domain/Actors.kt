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

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role

/**
 * Who performs a step. Grammar (whitespace-insensitive):
 * ```
 * expression := selector ( "|" selector )*          // also a YAML list of selectors
 * selector   := role [ "[" filter "]" ]
 * role       := admin | manager | employee
 * filter     := "*" | <department> | criterion ("," criterion)*
 * criterion  := dept=<department> | reg=invite|company_code | n=<1-based index>
 * ```
 * Examples: `admin`, `manager[IT]`, `employee[*]`, `employee[dept=IT, n=1]`, `employee[*] | manager[*]`.
 */
data class ActorExpression(
    val selectors: List<ActorSelector>,
    val raw: String,
)

data class ActorSelector(
    val role: Role,
    val department: String? = null,
    val registration: RegistrationMode? = null,
    /** 1-based index among the testers matching role/department/registration; null = all of them. */
    val nth: Int? = null,
)
