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

import az.petek.app.config.ResolvedAccount
import az.petek.app.config.ResolvedTarget
import az.petek.app.testing.PanelHarness
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.Flow
import az.petek.campaign.domain.FlowNames
import az.petek.campaign.domain.FlowStep
import az.petek.campaign.domain.OwnAccount
import az.petek.campaign.domain.SecretRef
import az.petek.campaign.domain.SignInMethod
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.TargetSpec
import az.petek.core.security.Secret
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class SignInChainTest {
    @TempDir
    lateinit var dir: Path

    private val open = mutableListOf<PanelHarness>()
    private val progress = CopyOnWriteArrayList<String>()
    private val sessions = CopyOnWriteArrayList<Pair<SessionOptions, FakeBrowserSession>>()

    @AfterEach
    fun close() = open.forEach { it.close() }

    private val site = URI("http://127.0.0.1:9")

    private fun harness(profile: (URI) -> ResolvedTarget?): PanelHarness =
        PanelHarness(dir, targets = listOfNotNull(profile(site))).also { open += it }

    /** Sessions whose login submit leaves the login page, as a site that accepts the password does. */
    private val factory =
        BrowserSessionFactory { options ->
            val fake = FakeBrowserSession(options.label)
            sessions += options to fake
            object : BrowserSession by fake {
                override suspend fun clickSelector(selector: String) {
                    fake.clickSelector(selector)
                    fake.url = "/dashboard"
                }
            }
        }

    private val companyCode = mapOf("company_code" to "ACME-42")

    private fun request(panel: PanelHarness) = RoleSessionRequest(panel.config.target, allowWrites = false, departments = emptyList())

    private fun source(
        name: String,
        answer: (RoleSessionRequest) -> RoleSessions,
        calls: MutableList<String>,
    ) = RoleSessionSource { request, _, _ ->
        calls += name
        answer(request)
    }

    @Test
    fun `methods are tried in the profile's order until one gives sessions, and each fallback is said`() =
        runBlocking<Unit> {
            val panel =
                harness {
                    ResolvedTarget(
                        TargetSpec("site", it, signIn = listOf(SignInMethod.OWN_ACCOUNTS, SignInMethod.TEST_COMPANY)),
                        null,
                        emptyList(),
                    )
                }
            val calls = mutableListOf<String>()
            val chain =
                SignInChain(
                    panel.panel.container,
                    mapOf(
                        SignInMethod.TEST_COMPANY to
                            source("test", { RoleSessions(mapOf("admin" to FakeBrowserSession()), { error("unused") }, null) }, calls),
                        SignInMethod.OWN_ACCOUNTS to source("own", { RoleSessions.none("profildə hesab verilməyib") }, calls),
                    ),
                )

            val opened = chain.open(request(panel), factory) { progress += it }

            calls shouldContainExactly listOf("own", "test")
            opened.sessions.keys shouldContainExactly listOf("admin")
            progress shouldContain "sahibin hesabları alınmadı: profildə hesab verilməyib"
            progress.last() shouldContain "Giriş yolu: test şirkəti (test API)"
        }

    @Test
    fun `when nothing gets in, the explorer walks anonymously and says why for every method`() =
        runBlocking<Unit> {
            val panel = harness { null }
            val chain =
                SignInChain(
                    panel.panel.container,
                    SignInMethod.entries.associateWith { method ->
                        RoleSessionSource { _, _, _ -> RoleSessions.none("${method.key} yox") }
                    },
                )

            val opened = chain.open(request(panel), factory) { progress += it }

            opened.sessions.keys.shouldBeEmpty()
            val note = checkNotNull(opened.note)
            note shouldContain "Kəşfiyyat anonim davam edir"
            note shouldContain "özü qeydiyyat: self_register yox"
        }

    @Test
    fun `an owner account signs in through the login form, saves its session and reuses it next time`() =
        runBlocking<Unit> {
            val panel =
                harness {
                    ResolvedTarget(
                        TargetSpec(
                            "site",
                            it,
                            accounts = listOf(OwnAccount("admin", "owner@example.com", SecretRef("ADMIN_PASSWORD"))),
                        ),
                        null,
                        listOf(ResolvedAccount("admin", "owner@example.com", Secret("s3cret-password"), null)),
                    )
                }
            val own = OwnAccountRoleSessions(panel.panel.container, { SetupProfile(TargetProfile.DEFAULT, "contract") })

            val first = own.open(request(panel), factory) { progress += it }

            first.sessions.keys shouldContainExactly listOf("admin")
            val (_, session) = sessions.single()
            session.actions.first() shouldBe "navigate /login"
            session.actions.any { it.startsWith("saveStorageState ") && it.endsWith("sessions/site/admin.json") } shouldBe true
            progress.joinToString() shouldNotContain "s3cret-password"
            first.close()

            val saved = panel.config.evidenceDir.resolve("sessions/site/admin.json")
            Files.createDirectories(saved.parent)
            Files.writeString(saved, "{}")
            sessions.clear()
            val second = own.open(request(panel), factory) { progress += it }

            second.sessions.keys shouldContainExactly listOf("admin")
            sessions.single().first.storageState shouldBe saved
            progress.last() shouldContain "saxlanmış sessiya işləyir"
        }

    @Test
    fun `an owner account signs in through the profile's own login flow with the account's fields`() =
        runBlocking<Unit> {
            val panel =
                harness {
                    ResolvedTarget(
                        TargetSpec("site", it),
                        null,
                        listOf(ResolvedAccount("admin", "owner@example.com", Secret("admin-password"), null, fields = companyCode)),
                    )
                }
            val login =
                listOf(
                    FlowStep.Goto("login"),
                    FlowStep.Fill("login.email", "{self.email}"),
                    FlowStep.Fill("login.password", "{self.password}"),
                    FlowStep.Fill("login.company_code", "{shared.company_code}"),
                    FlowStep.Click("login.submit"),
                    FlowStep.SaveSession,
                )
            val profile =
                TargetProfile.DEFAULT.copy(
                    selectors = mapOf("login.company_code" to "#companyCode"),
                    flows = TargetProfile.DEFAULT_FLOWS + (FlowNames.LOGIN to Flow(login)),
                )
            val own = OwnAccountRoleSessions(panel.panel.container, { SetupProfile(profile, "site") })

            val opened = own.open(request(panel), factory) { progress += it }

            opened.sessions.keys shouldContainExactly listOf("admin")
            sessions.single().second.actions shouldContain "fillSelector #companyCode=ACME-42"
            progress.last() shouldContain "sahibin hesabı ilə daxil olundu"
        }

    @Test
    fun `when the login form stays, the owner reads which field was left empty or what the site said`() =
        runBlocking<Unit> {
            val panel =
                harness {
                    ResolvedTarget(
                        TargetSpec("site", it),
                        null,
                        listOf(ResolvedAccount("admin", "owner@example.com", Secret("admin-password"), null)),
                    )
                }
            val stays = CopyOnWriteArrayList<FakeBrowserSession>()

            /** Pages that keep the login form after it is sent, as a site that refuses the login does. */
            fun staysOnLogin(page: FakeBrowserSession.() -> Unit) =
                BrowserSessionFactory { options -> FakeBrowserSession(options.label).apply(page).also { stays += it } }
            val own =
                OwnAccountRoleSessions(
                    panel.panel.container,
                    { SetupProfile(TargetProfile.DEFAULT, "contract") },
                    loginTimeout = 300.milliseconds,
                )

            val empty =
                own.open(
                    request(panel),
                    staysOnLogin {
                        counts[OwnAccountRoleSessions.INVALID_FIELDS] = 1
                        attributes[OwnAccountRoleSessions.INVALID_FIELDS to "name"] = "company_code"
                    },
                ) { progress += it }
            val refused =
                own.open(request(panel), staysOnLogin { selectorTexts["[data-testid=\"login-error\"]"] = "Şifrə yanlışdır" }) {
                    progress += it
                }

            empty.sessions.keys.shouldBeEmpty()
            empty.note.orEmpty() shouldContain "1 məcburi sahə boş və ya yanlış qaldı (məsələn `company_code`)"
            empty.note.orEmpty() shouldContain "targets/site.yaml"
            refused.note shouldBe "admin: sayt girişi qəbul etmədi: Şifrə yanlışdır"
            stays.all { it.closed } shouldBe true
        }

    @Test
    fun `the explorer uses its own account when the owner gave one, not the testers' accounts`() =
        runBlocking<Unit> {
            val panel =
                harness {
                    ResolvedTarget(
                        TargetSpec("site", it),
                        null,
                        listOf(
                            ResolvedAccount("admin", "owner@example.com", Secret("admin-password"), null),
                            ResolvedAccount("explorer", "explorer@example.com", Secret("explorer-password"), null),
                        ),
                    )
                }
            val own = OwnAccountRoleSessions(panel.panel.container, { SetupProfile(TargetProfile.DEFAULT, "contract") })

            val opened = own.open(request(panel), factory) { progress += it }

            opened.sessions.keys shouldContainExactly listOf("explorer")
            sessions.single().second.actions shouldContain "fillSelector [data-testid=\"login-email\"]=explorer@example.com"
        }

    @Test
    fun `a site without a profile or without accounts gives no own-account sessions`() =
        runBlocking<Unit> {
            val panel = harness { null }
            val own = OwnAccountRoleSessions(panel.panel.container, { SetupProfile(TargetProfile.DEFAULT, "contract") })

            own.open(request(panel), factory) { progress += it }.note shouldBe "sahibin hesabı verilməyib (paneldə və ya hədəf profilində)"
        }
}
