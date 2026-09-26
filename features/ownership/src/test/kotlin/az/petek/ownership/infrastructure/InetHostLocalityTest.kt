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

package az.petek.ownership.infrastructure

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InetHostLocalityTest {
    private val locality = InetHostLocality()

    @Test
    fun `loopback and private literals are local`() =
        runTest {
            locality.isLocal("127.0.0.1") shouldBe true
            locality.isLocal("::1") shouldBe true
            locality.isLocal("192.168.10.4") shouldBe true
        }

    @Test
    fun `a public literal is not local`() =
        runTest {
            locality.isLocal("8.8.8.8") shouldBe false
        }

    @Test
    fun `a name that does not resolve is not local`() =
        runTest {
            locality.isLocal("no-such-host.invalid") shouldBe false
        }
}
