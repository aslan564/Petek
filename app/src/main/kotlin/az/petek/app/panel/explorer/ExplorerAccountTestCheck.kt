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

package az.petek.app.panel.explorer

import az.petek.app.panel.PanelTargets
import az.petek.core.ids.RunId
import az.petek.explorer.domain.TestTargetCheck
import az.petek.explorer.domain.TestTargetVerdict
import az.petek.ownership.domain.OwnershipStatus
import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * On a site without companies, confirms the explorer's own account as the data its trial touch may write to (the
 * owner's decision of 2026-09-30, docs/PLAN.md): the account the explorer signed up with in this very exploration
 * ([run], the setup run that made it), on the configured site ([configured], `PETEK_TARGET`) whose owner proved it or
 * that is local ([ownership], ADR-0012). The trial touch then writes from that account alone and every text it types
 * carries its `Pətək sınaq` marker, so nobody else's data is touched and what it wrote is recognised. Reasons are in
 * Azerbaijani, since the panel shows them; they never contain the account's e-mail.
 */
internal class ExplorerAccountTestCheck(
    private val ownership: suspend (URI) -> OwnershipStatus,
    private val configured: URI,
    private val run: RunId,
) : TestTargetCheck {
    override suspend fun check(target: URI): TestTargetVerdict {
        if (!PanelTargets.sameSite(target, configured)) {
            return TestTargetVerdict.Refused("kəşfiyyatçının öz hesabı yalnız ${PanelTargets.site(configured)} saytında açılıb")
        }
        val status =
            try {
                ownership(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return TestTargetVerdict.Refused("saytın sahibliyi yoxlana bilmədi (${e::class.simpleName})")
            }
        val account = "the explorer's own account, made in this exploration (run ${run.value})"
        return when (status) {
            is OwnershipStatus.Unverified -> {
                TestTargetVerdict.Refused("saytın sahibliyi təsdiqlənməyib; şirkətsiz saytda sınaq toxunuşu yalnız sahibin saytında olur")
            }

            is OwnershipStatus.Exempt -> {
                TestTargetVerdict.Confirmed("$account, on the local site ${status.host}")
            }

            is OwnershipStatus.Verified -> {
                TestTargetVerdict.Confirmed("$account, on ${status.host}, whose owner proved it (${status.record.method.key})")
            }
        }
    }
}
