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

import az.petek.core.ids.RunId
import az.petek.core.testing.FakeHarnessClock
import az.petek.explorer.domain.TestTargetVerdict
import az.petek.ownership.testing.OwnershipTestKit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI

class ExplorerAccountTestCheckTest {
    private val site = URI("https://notes.example")
    private val run = RunId("run_own")
    private val clock = FakeHarnessClock()

    @Test
    fun `the explorer's own account on a site whose owner proved it may be written from`() =
        runTest {
            val verdict =
                ExplorerAccountTestCheck(
                    OwnershipTestKit.owned(clock)::check,
                    site,
                    run,
                ).check(URI("https://notes.example/notes"))

            verdict.shouldBeInstanceOf<TestTargetVerdict.Confirmed>().evidence shouldBe
                "the explorer's own account, made in this exploration (run run_own), on notes.example, whose owner proved it " +
                "(file)"
        }

    @Test
    fun `a local site needs no proof`() =
        runTest {
            val local = URI("http://127.0.0.1:18090")
            val ownership = OwnershipTestKit.unowned(clock, local = setOf("127.0.0.1"))

            val verdict = ExplorerAccountTestCheck(ownership::check, local, run).check(local)

            verdict.shouldBeInstanceOf<TestTargetVerdict.Confirmed>().evidence shouldContain "on the local site 127.0.0.1"
        }

    @Test
    fun `a site whose owner did not prove it is refused, and another site is refused without asking`() =
        runTest {
            val asked = mutableListOf<URI>()
            val ownership = OwnershipTestKit.unowned(clock)
            val check =
                ExplorerAccountTestCheck(
                    { target -> ownership.check(target).also { asked += target } },
                    site,
                    run,
                )

            check.check(URI("https://portal.example")).shouldBeInstanceOf<TestTargetVerdict.Refused>().reason shouldContain
                "yalnız https://notes.example:443"
            asked.shouldBeEmpty()
            check.check(site).shouldBeInstanceOf<TestTargetVerdict.Refused>().reason shouldContain "sahibliyi təsdiqlənməyib"
        }

    @Test
    fun `an ownership look that fails refuses the touch`() =
        runTest {
            val verdict = ExplorerAccountTestCheck({ error("DNS is down") }, site, run).check(site)

            verdict.shouldBeInstanceOf<TestTargetVerdict.Refused>().reason shouldContain "IllegalStateException"
        }
}
