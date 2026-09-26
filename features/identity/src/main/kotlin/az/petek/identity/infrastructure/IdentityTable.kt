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

package az.petek.identity.infrastructure

import org.jetbrains.exposed.v1.core.Table

/**
 * `identity` table (docs/PLAN.md "Reyestrin sahələri"). E-mails are unique across all runs and compared without
 * regard to ASCII letter case, like mail servers do; display names are unique within a run.
 * Passwords are stored as plain text on purpose: they belong to throw-away test identities and the harness must
 * read them back to log in.
 */
internal object IdentityTable : Table("identity") {
    val runId = varchar("run_id", 128)
    val agentId = varchar("agent_id", AGENT_ID_LENGTH)
    val displayName = varchar("display_name", 255)
    val email = varchar("email", 320, collate = "NOCASE").uniqueIndex("identity_email_unique")
    val password = varchar("password", 128)
    val phone = varchar("phone", 32)
    val role = varchar("role", 16)
    val department = varchar("department", 255).nullable()
    val registration = varchar("registration", 16)
    val status = varchar("status", 16)
    val statusReason = text("status_reason").nullable()
    val storageStatePath = text("storage_state_path").nullable()

    /** Added after the first release; older databases get it with the default (`SqliteDatabase.createMissing`). */
    val workspaceId = text("workspace_id").default("local")

    override val primaryKey = PrimaryKey(runId, agentId, name = "identity_run_agent_pk")

    init {
        uniqueIndex("identity_run_display_name_unique", runId, displayName)
    }

    /**
     * Room for every agent id a registry can have (`a` + up to 10 digits of an [Int] index). SQLite does not enforce
     * the declared length, so databases created with the earlier, narrower column keep working.
     */
    private const val AGENT_ID_LENGTH = 16
}
