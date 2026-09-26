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

package az.petek.oracle.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class DefaultJsonFieldSelectorTest {
    private val selector = DefaultJsonFieldSelector()

    private val ticket =
        Json.parseToJsonElement(
            """
            {
              "id": "t1",
              "status": "in_progress",
              "count": 3,
              "approved": false,
              "assignee": {"email": "rena@test.portal.example", "name": "Rəna"},
              "history": [
                {"from": "open", "to": "in_progress", "by": "a@x.az"},
                {"from": "in_progress", "to": "approved", "by": "b@x.az"}
              ],
              "receipts": [{"email": "1@x.az"}, {"email": "2@x.az"}, {"email": "3@x.az"}],
              "matrix": [[1, 2], [3, 4]],
              "nothing": null,
              "first name": "Əli"
            }
            """.trimIndent(),
        )

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "status, in_progress",
        "id, t1",
        "count, 3",
        "approved, false",
        "assignee.email, rena@test.portal.example",
        "history[0].to, in_progress",
        "history[1].by, b@x.az",
        "history[-1].to, approved",
        "history[-2].from, open",
        "receipts[2].email, 3@x.az",
        "receipts.1.email, 2@x.az",
        "matrix[1][0], 3",
        "first name, Əli",
        "'  assignee.name  ', Rəna",
    )
    fun `selects values by dotted path and index`(
        path: String,
        expected: String,
    ) {
        (selector.select(ticket, path) as JsonPrimitive).content shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "missing",
            "assignee.phone",
            "history[5]",
            "history[-3]",
            "history[99999999999]",
            "status.length",
            "status[0]",
            "assignee[0]",
            "nothing.email",
            "receipts.x",
            "matrix[0][2]",
        ],
    )
    fun `missing values select null`(path: String) {
        selector.select(ticket, path).shouldBeNull()
    }

    @Test
    fun `an element selects whole objects and arrays`() {
        selector.select(ticket, "receipts[2]") shouldBe Json.parseToJsonElement("""{"email": "3@x.az"}""")
        selector.select(ticket, "matrix[0]") shouldBe Json.parseToJsonElement("[1, 2]")
    }

    @Test
    fun `an explicit JSON null is present and selects JsonNull`() {
        selector.select(ticket, "nothing") shouldBe JsonNull
    }

    @Test
    fun `a blank path selects the root`() {
        selector.select(ticket, "") shouldBe ticket
        selector.select(ticket, "   ") shouldBe ticket
    }

    @Test
    fun `a root array is indexed with a leading index`() {
        val receipts = Json.parseToJsonElement("""[{"email": "a@x.az"}, {"email": "b@x.az"}]""")

        (selector.select(receipts, "[1].email") as JsonPrimitive).content shouldBe "b@x.az"
        (selector.select(receipts, "[-1].email") as JsonPrimitive).content shouldBe "b@x.az"
        selector.select(receipts, "email").shouldBeNull()
    }

    @Test
    fun `a primitive root has no fields`() {
        selector.select(JsonPrimitive("published"), "status").shouldBeNull()
        selector.select(JsonPrimitive("published"), "") shouldBe JsonPrimitive("published")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["a..b", ".a", "a.", "a[x]", "a[1", "a]", "a[1]b", "a.[0]", "[]", "a[ 1 ]", "history[0]..to"])
    fun `a malformed path is rejected with the path in the message`(path: String) {
        val error = shouldThrow<IllegalArgumentException> { selector.select(ticket, path) }

        error.message shouldContain "'$path'"
    }
}
