/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
