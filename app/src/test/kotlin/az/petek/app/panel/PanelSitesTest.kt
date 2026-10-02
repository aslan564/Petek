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

package az.petek.app.panel

import az.petek.app.config.ConfigException
import az.petek.app.config.ConfigLoader
import az.petek.app.config.EnvFile
import az.petek.app.config.IdentitySecretSource
import az.petek.app.config.MailSource
import az.petek.app.config.PetekConfig
import az.petek.app.config.TargetProfileConfig
import az.petek.app.di.AppContainer
import az.petek.core.security.Secret
import az.petek.dashboard.domain.AccountRequest
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PanelUnavailableException
import az.petek.dashboard.domain.SiteRequest
import az.petek.dashboard.domain.SiteView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Faza 23: the sites the panel knows, each with its own settings, and a new one known without a restart. */
class PanelSitesTest {
    @TempDir
    lateinit var dir: Path

    private val env: Path get() = dir.resolve(".env")
    private val targets: Path get() = dir.resolve("targets")

    private fun load(): PetekConfig = ConfigLoader(emptyMap(), dir, IdentitySecretSource { error("the file names its secret") }).load(env)

    private fun adapter(
        container: AppContainer,
        reload: (() -> PetekConfig)? = ::load,
    ) = PanelSitesAdapter(container, env, targets, reload)

    @BeforeEach
    fun files() {
        Files.writeString(
            env,
            "PETEK_TARGET=http://127.0.0.1:9\nPETEK_IDENTITY_SECRET=sites-identity-secret-0123\nPETEK_EVIDENCE_DIR=evidence\n" +
                "PETEK_TEST_TOKEN=own-token-123\nPETEK_PRODUCTION_HOSTS=portal.example\nSHOP_TOKEN=shop-token-123\n",
        )
        Files.createDirectories(targets)
        Files.writeString(
            targets.resolve("shop.yaml"),
            """
            target:
              name: shop
              url: https://stage.shop.example
              mail: {source: manual}
              test_api: {token: '${'$'}{SHOP_TOKEN}'}
              accounts:
                - {role: admin, email: owner@shop.example, password: '${'$'}{SHOP_TOKEN}'}
            """.trimIndent() + "\n",
        )
        Files.writeString(targets.resolve("blog.yaml"), "target: {name: blog, url: 'https://blog.example'}\n")
    }

    @Test
    fun `the panel's own site comes first, then every profile with the settings a run there takes`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val sites = adapter(container).sites()

                sites.map { it.name } shouldContainExactly listOf("127-0-0-1", "blog", "shop")
                sites[0] shouldBe SiteView("127-0-0-1", "http://127.0.0.1:9", own = true, null, testApi = true, "mailpit", 0)
                // The blog names no token: the panel's own is never lent to it.
                sites[1] shouldBe SiteView("blog", "https://blog.example", own = false, "targets/blog.yaml", testApi = false, "mailpit", 0)
                sites[2] shouldBe
                    SiteView("shop", "https://stage.shop.example", own = false, "targets/shop.yaml", testApi = true, "manual", 1)
            }
        }

    @Test
    fun `a site added in the panel is its own profile, its token only in the configuration file, known at once`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val request = SiteRequest("notes", "https://notes.example", mail = "manual", token = "notes-token-0123456789")

                val sites = adapter(container).addSite(request)

                sites.single { it.name == "notes" } shouldBe
                    SiteView("notes", "https://notes.example", own = false, "targets/notes.yaml", testApi = true, "manual", 0)
                val profile = Files.readString(targets.resolve("notes.yaml"))
                profile shouldContain "\${PETEK_SITE_NOTES_TOKEN}"
                profile shouldNotContain "notes-token"
                EnvFile.load(env)["PETEK_SITE_NOTES_TOKEN"] shouldBe "notes-token-0123456789"
                request.toString() shouldNotContain "notes-token"
                // The running panel takes it: a run there gets its own token and mail.
                val there = TargetProfileConfig.forTarget(container.config, URI("https://notes.example"))
                there.testToken shouldBe Secret("notes-token-0123456789")
                there.mailSource shouldBe MailSource.MANUAL
                // ... and so does the next start.
                load()
                    .profileFor(URI("https://notes.example"))
                    .shouldNotBeNull()
                    .spec.name shouldBe "notes"
            }
        }

    @Test
    fun `a blank name is made from the host, and a site given no token has no test API`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val before = Files.readString(env)

                val sites = adapter(container).addSite(SiteRequest(" ", "https://staging.news.example/"))

                val added = sites.single { it.name == "staging-news-example" }
                added.testApi shouldBe false
                Files.readString(targets.resolve("staging-news-example.yaml")) shouldNotContain "test_api"
                Files.readString(env) shouldBe before
            }
        }

    @Test
    fun `the panel's own site, a known site or name, a production host and an incomplete mail are refused, and nothing is written`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val sites = adapter(container)
                val envBefore = Files.readString(env)

                fun refused(request: SiteRequest): List<String> =
                    runBlocking { shouldThrow<PanelRequestException> { sites.addSite(request) } }.problems.map { it.field }

                refused(SiteRequest("own", "http://127.0.0.1:9/login")) shouldBe listOf("url")
                refused(SiteRequest("shop-2", "https://stage.shop.example")) shouldBe listOf("url")
                refused(SiteRequest("shop", "https://other.example")) shouldBe listOf("name")
                refused(SiteRequest("Not OK!", "https://other.example")) shouldBe listOf("name")
                refused(SiteRequest("live", "https://portal.example")) shouldBe listOf("url")
                refused(SiteRequest("other", "https://other.example", mail = "test-api")) shouldBe listOf("token")
                refused(SiteRequest("other", "https://other.example", mail = "pigeon")) shouldBe listOf("mail")
                refused(SiteRequest("other", "https://other.example", mail = "imap")) shouldBe listOf("mail")
                refused(SiteRequest("other", "not a site")) shouldBe listOf("url")

                Files.list(targets).use { it.map { file -> file.fileName.toString() }.sorted().toList() } shouldBe
                    listOf("blog.yaml", "shop.yaml")
                Files.readString(env) shouldBe envBefore
                container.config.targets shouldHaveSize 2
            }
        }

    @Test
    fun `a configuration that does not load with the new site puts both files back`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val envBefore = Files.readString(env)
                val broken = adapter(container) { throw ConfigException(listOf("PETEK_SOMETHING is broken")) }

                val refused =
                    shouldThrow<PanelRequestException> {
                        broken.addSite(SiteRequest("notes", "https://notes.example", token = "notes-token-0123456789"))
                    }

                refused.problems.single().message shouldContain "PETEK_SOMETHING is broken"
                Files.exists(targets.resolve("notes.yaml")) shouldBe false
                Files.readString(env) shouldBe envBefore
                container.config.profileFor(URI("https://notes.example")) shouldBe null
            }
        }

    @Test
    fun `where the configuration cannot be read again the sites are only listed`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val listed = adapter(container, reload = null)

                listed.sites() shouldHaveSize 3
                shouldThrow<PanelUnavailableException> { listed.addSite(SiteRequest("notes", "https://notes.example")) }
                Files.exists(targets.resolve("notes.yaml")) shouldBe false
            }
        }

    @Test
    fun `an account given for a new site shows the site at once and is offered once`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val sites = adapter(container)
                val accounts = OwnerAccounts({ container.config }, env, targets, onWritten = sites::takeProfiles)

                accounts.add(AccountRequest("https://forum.example", "admin", "owner@forum.example", "forum-pass-123"))

                sites.sites().map { it.name } shouldContainExactly listOf("127-0-0-1", "blog", "forum-example", "shop")
                accounts.accountsFor(URI("https://forum.example")).map { it.email } shouldBe listOf("owner@forum.example")
            }
        }
}
