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

package az.petek.oracle.infrastructure

import az.petek.oracle.domain.SeedCompanyRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request bodies of the target test API (docs/TARGET_CONTRACT.md section 4, snake_case). Responses are read as
// JsonObject so that numeric ids, missing optional fields and extra keys never break a run.

@Serializable
internal data class SeedCompanyBody(
    @SerialName("company_id") val companyId: String,
    @SerialName("departments") val departments: List<String>,
    @SerialName("invites") val invites: List<SeedInviteBody>,
) {
    companion object {
        fun of(request: SeedCompanyRequest) =
            SeedCompanyBody(
                companyId = request.companyId,
                departments = request.departments,
                invites = request.invites.map { SeedInviteBody(it.email, it.name, it.role, it.department) },
            )
    }
}

/** `department` is left out (not sent as `null`) for invitees without one, e.g. a second admin. */
@Serializable
internal data class SeedInviteBody(
    @SerialName("email") val email: String,
    @SerialName("name") val name: String,
    @SerialName("role") val role: String,
    @SerialName("department") val department: String? = null,
)
