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

package az.petek.campaign.infrastructure

import az.petek.campaign.domain.OwnAccount
import az.petek.campaign.domain.SecretRef
import az.petek.campaign.domain.SignInMethod
import az.petek.campaign.domain.TargetMail
import az.petek.campaign.domain.TargetSpec
import az.petek.campaign.domain.TargetSpecException
import az.petek.campaign.testing.repoFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class YamlTargetSpecSourceTest {
    @TempDir
    lateinit var dir: Path

    private val source = YamlTargetSpecSource()

    private fun file(
        name: String,
        text: String,
    ): Path = Files.writeString(dir.resolve(name), text.trimIndent())

    @Test
    fun `a full profile is read with its secret references, never values`() {
        val spec =
            source.load(
                file(
                    "kadrohr.yaml",
                    """
                    target:
                      name: kadrohr
                      url: https://staging.kadrohr.com
                      api_url: https://api.staging.kadrohr.com
                      production_hosts: [KadroHR.com, www.kadrohr.com]
                      mail: {source: test-api, domain: Test.KadroHR.com}
                      test_api: {token: '${'$'}{PETEK_TEST_TOKEN_KADROHR}'}
                      sign_in: [own_accounts, anonymous]
                      accounts:
                        - {role: admin, email: owner@example.com, password: '${'$'}{PETEK_ACC_ADMIN}'}
                        - {role: hr, storage_state: sessions/hr.json}
                      profile: scenarios/kadrohr.yaml
                    """,
                ),
            )

        spec shouldBe
            TargetSpec(
                name = "kadrohr",
                url = URI("https://staging.kadrohr.com"),
                apiUrl = URI("https://api.staging.kadrohr.com"),
                productionHosts = setOf("kadrohr.com", "www.kadrohr.com"),
                mail = TargetMail("test-api", "test.kadrohr.com", null),
                testToken = SecretRef("PETEK_TEST_TOKEN_KADROHR"),
                signIn = listOf(SignInMethod.OWN_ACCOUNTS, SignInMethod.ANONYMOUS),
                accounts =
                    listOf(
                        OwnAccount("admin", "owner@example.com", SecretRef("PETEK_ACC_ADMIN")),
                        OwnAccount("hr", storageState = "sessions/hr.json"),
                    ),
                profile = "scenarios/kadrohr.yaml",
            )
    }

    @Test
    fun `a minimal profile tries every sign-in method in the default order`() {
        val spec = source.load(file("shop.yaml", "target: {name: shop, url: 'http://localhost:8080'}"))

        spec.signIn shouldBe SignInMethod.DEFAULT_CHAIN
        spec.accounts.shouldBeEmpty()
    }

    @Test
    fun `a password written out, an unknown key or method and a bad URL are refused with their lines`() {
        val error =
            shouldThrow<TargetSpecException> {
                source.load(
                    file(
                        "bad.yaml",
                        """
                        target:
                          name: bad
                          url: ftp://bad.example
                          colour: blue
                          sign_in: [magic]
                          accounts:
                            - {role: admin, email: a@b.az, password: hunter2}
                        """,
                    ),
                )
            }

        val messages = error.issues.map { it.toString() }
        messages.any { it.startsWith("line 3:") && it.contains("absolute http(s) URL") } shouldBe true
        messages.any { it.contains("unknown key 'colour'") } shouldBe true
        messages.any { it.contains("unknown sign_in method 'magic'") } shouldBe true
        messages.any { it.startsWith("line 7:") && it.contains("reference to .env, never the secret itself") } shouldBe true
        error.message shouldContain "bad.yaml"
        (error.message ?: "").contains("hunter2") shouldBe false
    }

    @Test
    fun `an account's other login values are read as its fields, and a bad field name is refused`() {
        val spec =
            source.load(
                file(
                    "hr.yaml",
                    """
                    target:
                      name: hr
                      url: https://staging.hr.example
                      accounts:
                        - role: admin
                          email: owner@example.com
                          password: '${'$'}{PETEK_ACC_ADMIN}'
                          fields: {company_code: ACME-42, workspace: main}
                    """,
                ),
            )

        spec.accounts.single().fields shouldBe mapOf("company_code" to "ACME-42", "workspace" to "main")

        val error =
            shouldThrow<TargetSpecException> {
                source.load(
                    file(
                        "bad-fields.yaml",
                        """
                        target:
                          name: bad
                          url: https://staging.hr.example
                          accounts:
                            - {role: admin, email: a@b.az, password: '${'$'}{PETEK_ACC_ADMIN}', fields: {Company-Code: X}}
                        """,
                    ),
                )
            }
        error.issues.map { it.toString() }.any { it.startsWith("line 5:") && it.contains("Company-Code") } shouldBe true
    }

    @Test
    fun `every profile of a directory is loaded by name, and two with one name are refused`() {
        file("b.yaml", "target: {name: beta, url: 'https://b.example'}")
        file("a.yml", "target: {name: alpha, url: 'https://a.example'}")
        Files.writeString(dir.resolve("notes.txt"), "not a profile")

        source.loadAll(dir).map { it.name } shouldContainExactly listOf("alpha", "beta")
        source.loadAll(dir.resolve("absent")).shouldBeEmpty()

        file("c.yaml", "target: {name: alpha, url: 'https://c.example'}")
        shouldThrow<TargetSpecException> { source.loadAll(dir) }.message shouldContain "two profiles are named 'alpha'"
    }

    @Test
    fun `the repository's KadroHR profile loads and points at the real campaign`() {
        val spec = source.load(repoFile("targets/kadrohr.yaml"))

        spec.name shouldBe "kadrohr"
        spec.testToken shouldBe SecretRef("PETEK_TEST_TOKEN")
        Files.exists(repoFile(checkNotNull(spec.profile))) shouldBe true
    }
}
