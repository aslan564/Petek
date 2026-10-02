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

import az.petek.core.model.RegistrationMode
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.neq

/**
 * `identity` table (docs/PLAN.md "Reyestrin sahələri"). The e-mails of the testers Pətək generates are unique across all
 * runs ([GENERATED_EMAIL_INDEX]) and compared without regard to ASCII letter case, like mail servers do; a `login`
 * tester's e-mail is the owner's account, stored again by every run that signs in with it. Display names are unique
 * within a run.
 *
 * A generated tester's password is stored as plain text on purpose: it belongs to a throw-away test identity, and triage
 * reads it back to mask it in what it shows the model. A `login` tester's password is the owner's own and is never
 * stored: its row holds an empty one ([az.petek.identity.domain.Identity.asStored]).
 */
internal object IdentityTable : Table("identity") {
    val runId = varchar("run_id", 128)
    val agentId = varchar("agent_id", AGENT_ID_LENGTH)
    val displayName = varchar("display_name", 255)
    val email = varchar("email", 320, collate = "NOCASE")
    val password = varchar("password", 128)
    val phone = varchar("phone", 32)
    val role = varchar("role", 16)
    val department = varchar("department", 255).nullable()

    /** The tester's [RegistrationMode.key]. */
    val registration = varchar("registration", 16)
    val status = varchar("status", 16)
    val statusReason = text("status_reason").nullable()
    val storageStatePath = text("storage_state_path").nullable()

    /** Added after the first release; older databases get it with the default (`SqliteDatabase.createMissing`). */
    val workspaceId = text("workspace_id").default("local")

    override val primaryKey = PrimaryKey(runId, agentId, name = "identity_run_agent_pk")

    /**
     * Unique e-mails of the generated testers: a partial index over every row but the owner's accounts (`login`), on
     * the column's `NOCASE` collation. It replaced [LEGACY_EMAIL_INDEX]; [SqliteIdentityRepository] moves a database
     * created before over when it opens it.
     */
    const val GENERATED_EMAIL_INDEX = "identity_generated_email_unique"

    /**
     * The e-mail index of the first releases: every e-mail unique across all runs, the owner's accounts' too, so a
     * campaign whose testers sign in with them could run only once per database.
     */
    const val LEGACY_EMAIL_INDEX = "identity_email_unique"

    init {
        uniqueIndex(GENERATED_EMAIL_INDEX, email, filterCondition = { registration neq RegistrationMode.LOGIN.key })
        uniqueIndex("identity_run_display_name_unique", runId, displayName)
    }

    /**
     * Room for every agent id a registry can have (`a` + up to 10 digits of an [Int] index). SQLite does not enforce
     * the declared length, so databases created with the earlier, narrower column keep working.
     */
    private const val AGENT_ID_LENGTH = 16
}
