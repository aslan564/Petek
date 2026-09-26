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

import az.petek.app.config.EnvFile
import az.petek.app.diagnostics.TargetAnswer
import az.petek.app.diagnostics.TargetReachability
import az.petek.app.init.InitTemplates
import az.petek.dashboard.domain.SetupAnswer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger

class PanelSetupTest {
    @TempDir
    lateinit var dir: Path

    private val env by lazy { dir.resolve(".env") }
    private val opened = AtomicInteger()

    private fun setup(reachability: TargetReachability = TargetReachability.ALWAYS) =
        PanelSetup(env, InitTemplates.bundled(), reachability) {
            opened.incrementAndGet()
            URI("http://127.0.0.1:7071/")
        }

    @Test
    fun `the site the owner names is written to env from the template and the panel opens for it, once`() =
        runBlocking<Unit> {
            val setup = setup()

            setup.configure("  https://staging.shop.example  ") shouldBe SetupAnswer.Ready("http://127.0.0.1:7071/")
            setup.configure("https://another.example") shouldBe SetupAnswer.Ready("http://127.0.0.1:7071/")

            opened.get() shouldBe 1
            EnvFile.load(env)["PETEK_TARGET"] shouldBe "https://staging.shop.example"
            Files.readString(env) shouldContain "PETEK_ORACLE="
            PosixFilePermissions.toString(Files.getPosixFilePermissions(env)) shouldBe "rw-------"
        }

    @Test
    fun `an address that is not a full web address is refused and nothing is written`() =
        runBlocking<Unit> {
            val answer = setup().configure("ftp://files.example")

            answer.shouldBeInstanceOf<SetupAnswer.Refused>().message shouldContain "http:// və ya https://"
            Files.exists(env) shouldBe false
            opened.get() shouldBe 0
        }

    @Test
    fun `a site that does not answer is refused with the reason and nothing is written`() =
        runBlocking<Unit> {
            val down = TargetReachability { TargetAnswer.Unreachable("HTTP 403: Cloudflare error 1000: DNS points to prohibited IP") }

            val answer = setup(down).configure("https://kodcraft.example")

            answer.shouldBeInstanceOf<SetupAnswer.Refused>().message shouldContain "Cloudflare error 1000"
            Files.exists(env) shouldBe false
            opened.get() shouldBe 0
        }

    @Test
    fun `an env file that appeared meanwhile is never overwritten`() =
        runBlocking<Unit> {
            Files.writeString(env, "PETEK_TARGET=https://mine.example\n")

            val answer = setup().configure("https://staging.shop.example")

            answer.shouldBeInstanceOf<SetupAnswer.Refused>().message shouldContain "artıq var"
            Files.readString(env) shouldBe "PETEK_TARGET=https://mine.example\n"
            opened.get() shouldBe 0
        }
}
