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
import az.petek.explorer.domain.TestTargetCheck
import az.petek.explorer.domain.TestTargetVerdict
import az.petek.oracle.domain.TargetOracle
import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * Confirms, through the target's own test API, that the explorer's trial touch writes into test data only
 * (docs/TARGET_CONTRACT.md: a company is test data when the target flags it `is_test`). The company asked about is the
 * one the role sessions belong to ([companyOwner], the e-mail of its owner); every submit of the trial touch is made in
 * it, and it is torn down afterwards.
 *
 * The test token belongs to the configured site ([oracleSite], `PETEK_TARGET`): a target on any other site is refused
 * without a request, so the token never travels to a site it was not issued for. Reasons are in Azerbaijani, since the
 * panel shows them; they never contain the owner's e-mail.
 */
internal class OracleTestTargetCheck(
    private val oracle: TargetOracle,
    private val oracleSite: URI,
    private val companyOwner: String?,
) : TestTargetCheck {
    override suspend fun check(target: URI): TestTargetVerdict {
        if (!PanelTargets.sameSite(target, oracleSite)) {
            return TestTargetVerdict.Refused(
                "test API-nin açarı (PETEK_TEST_TOKEN) yalnız ${PanelTargets.site(oracleSite)} üçündür; başqa sayta göndərilmir",
            )
        }
        if (!oracle.isAvailable) return TestTargetVerdict.Refused("PETEK_TEST_TOKEN qurulmayıb, test API-si istifadə edilə bilməz")
        val owner = companyOwner ?: return TestTargetVerdict.Refused("bu kəşfiyyat üçün test şirkəti yaradılmayıb")
        val company =
            try {
                oracle.companyByOwner(owner)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return TestTargetVerdict.Refused("test API cavab vermədi (${e::class.simpleName})")
            }
        return when {
            company == null -> TestTargetVerdict.Refused("test API bu kəşfiyyatın şirkətini tanımır")
            !company.isTest -> TestTargetVerdict.Refused("şirkət ${company.id} is_test deyil")
            else -> TestTargetVerdict.Confirmed("company ${company.id} is_test=true")
        }
    }
}
