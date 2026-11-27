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

package az.petek.faketarget

import az.petek.faketarget.notes.FakeNotesServer
import az.petek.faketarget.notes.NotesBug
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class FakeNotesServerTest {
    private val http = HttpClient.newHttpClient()

    private fun FakeNotesServer.welcome(): String =
        http.send(HttpRequest.newBuilder(baseUrl.resolve("/")).build(), HttpResponse.BodyHandlers.ofString()).body()

    @Test
    fun `a new release on the same address moves the sign-up link, and the clock shows only when asked for`() {
        FakeNotesServer(liveClock = true).start().use { site ->
            site.welcome().let {
                it shouldContain "data-testid=\"welcome-clock\""
                it shouldContain "Bu gün: "
                it shouldNotContain "margin-top:40px"
            }

            site.deploy(setOf(NotesBug.SHIFTED_SIGN_UP))

            site.welcome() shouldContain "margin-top:40px"
        }
        FakeNotesServer().start().use { site -> site.welcome() shouldNotContain "welcome-clock" }
    }
}
