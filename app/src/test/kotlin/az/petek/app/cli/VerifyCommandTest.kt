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

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import az.petek.core.testing.FakeHarnessClock
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.testing.OwnershipTestKit
import az.petek.ownership.testing.ScriptedOwnershipProbe
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class VerifyCommandTest {
    @TempDir
    lateinit var dir: Path

    private val zeroProof = "petek-verification=" + "0".repeat(32)

    private fun cli(
        found: OwnershipMethod?,
        local: Set<String> = emptySet(),
    ) = CliHarness(
        dir,
        mapOf("PETEK_TARGET" to "https://stage.example.com"),
        ownership = OwnershipTestKit.siteOwnership(FakeHarnessClock(), ScriptedOwnershipProbe(found), local),
    )

    @Test
    fun `an unproved site shows the file, the DNS record and where Pətək looked, with exit code 1`() =
        runBlocking<Unit> {
            val result = cli(found = null).run("verify")

            result.statusCode shouldBe ExitCodes.FAILURE
            result.stdout shouldContain "Ownership: not proved. Pətək only reads this site until its owner publishes one of:"
            result.stdout shouldContain "the file https://stage.example.com/.well-known/petek-verification.txt with the line:"
            result.stdout shouldContain "the DNS TXT record _petek-verification.stage.example.com with the value:"
            result.stdout shouldContain zeroProof
            result.stdout shouldContain "Looked for it:"
        }

    @Test
    fun `a proved site may run full tests`() =
        runBlocking<Unit> {
            val result = cli(found = OwnershipMethod.DNS_TXT).run("verify")

            result.statusCode shouldBe ExitCodes.OK
            result.stdout shouldContain "Ownership: proved by the dns on "
        }

    @Test
    fun `a local site needs no proof`() =
        runBlocking<Unit> {
            val result = cli(found = null, local = setOf("stage.example.com")).run("verify")

            result.statusCode shouldBe ExitCodes.OK
            result.stdout shouldContain "no proof needed"
        }

    @Test
    fun `--json gives the status, the proof and what was looked at`() =
        runBlocking<Unit> {
            val result = cli(found = null).run("--json", "verify")

            result.statusCode shouldBe ExitCodes.FAILURE
            val document = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            document["host"]!!.jsonPrimitive.content shouldBe "stage.example.com"
            document["status"]!!.jsonPrimitive.content shouldBe "unverified"
            document["allowsWrites"]!!.jsonPrimitive.boolean shouldBe false
            val proof = document["proof"]!!.jsonObject
            proof["line"]!!.jsonPrimitive.content shouldBe zeroProof
            proof["file"]!!.jsonPrimitive.content shouldBe "https://stage.example.com/.well-known/petek-verification.txt"
            proof["dnsName"]!!.jsonPrimitive.content shouldBe "_petek-verification.stage.example.com"
            document["looked"]!!.jsonArray.size shouldBe 1
        }

    @Test
    fun `a production host is refused before anything is looked at`() =
        runBlocking<Unit> {
            val probe = ScriptedOwnershipProbe()
            val cli =
                CliHarness(
                    dir,
                    mapOf("PETEK_TARGET" to "https://kadrohr.com", "PETEK_PRODUCTION_HOSTS" to "kadrohr.com"),
                    ownership = OwnershipTestKit.siteOwnership(FakeHarnessClock(), probe),
                )

            cli.run("verify").statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            probe.looks.size shouldBe 0
        }
}
