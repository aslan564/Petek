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

package az.petek.mail.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Behaves like Mailpit v1.31 where Pətək cares: `to:` is a case-insensitive substring match, search results are
 * newest first and carry extra fields, `GET message/{ID}` marks the message read, and read state is set with
 * [readStatusPath] (`PUT /api/v1/messages` in real Mailpit).
 */
class FakeMailpit(
    private val webroot: String = "",
    private val readStatusPath: String = "/api/v1/messages",
) {
    val messages = CopyOnWriteArrayList<Stored>()

    class Stored(
        val id: String,
        val to: List<String>,
        val subject: String,
        val created: String,
        val text: String,
        val html: String,
        @Volatile var read: Boolean = false,
    )

    fun add(message: Stored): Stored = message.also { messages += it }

    fun handle(request: RecordedRequest): StubResponse {
        val path = request.path.removePrefix(webroot)
        return when {
            request.method == "GET" && path == "/api/v1/search" -> search(request.query["query"]?.single().orEmpty())
            request.method == "GET" && path.startsWith("/api/v1/message/") -> message(path.removePrefix("/api/v1/message/"))
            request.method == "PUT" && path == readStatusPath -> setRead(request.body)
            path == "/api/v1/messages" -> StubResponse(405, "Method Not Allowed", "text/plain")
            else -> StubResponse(404, "404 page not found", "text/plain")
        }
    }

    private fun search(query: String): StubResponse {
        val term = query.removePrefix("to:").trim('"')
        val found = messages.filter { m -> m.to.any { it.contains(term, ignoreCase = true) } }.sortedByDescending { it.created }
        val body =
            buildJsonObject {
                put("total", messages.size)
                put("unread", messages.count { !it.read })
                put("count", found.size)
                put("messages_count", found.size)
                put("start", 0)
                put("tags", JsonArray(emptyList()))
                put(
                    "messages",
                    buildJsonArray {
                        found.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("ID", m.id)
                                    put("MessageID", "${m.id}@mailpit")
                                    put("Read", m.read)
                                    put("From", address("Demo Portal", "noreply@portal.example"))
                                    put("To", addresses(m.to))
                                    put("Cc", JsonNull)
                                    put("Subject", m.subject)
                                    put("Created", m.created)
                                    put("Tags", JsonArray(emptyList()))
                                    put("Size", 1234)
                                    put("Attachments", 0)
                                    put("Snippet", m.text.take(40))
                                },
                            )
                        }
                    },
                )
            }
        return StubResponse(body = body.toString())
    }

    private fun message(id: String): StubResponse {
        val m = messages.firstOrNull { it.id == id } ?: return StubResponse(404, "message not found", "text/plain")
        m.read = true // like Mailpit: fetching the message marks it read
        val body =
            buildJsonObject {
                put("ID", m.id)
                put("MessageID", "${m.id}@mailpit")
                put("From", address("Demo Portal", "noreply@portal.example"))
                put("To", addresses(m.to))
                put("Cc", JsonNull)
                put("Bcc", JsonNull)
                put("Subject", m.subject)
                put("Date", m.created)
                put("Text", m.text)
                put("HTML", m.html)
                put("Size", 1234)
                put("Attachments", JsonArray(emptyList()))
            }
        return StubResponse(body = body.toString())
    }

    private fun setRead(body: String): StubResponse {
        val request = Json.parseToJsonElement(body).jsonObject
        val ids = request.getValue("IDs").jsonArray.map { it.jsonPrimitive.content }
        val read = request.getValue("Read").jsonPrimitive.boolean
        if (ids.isEmpty()) return StubResponse(400, "test double refuses to update every message", "text/plain")
        messages.filter { it.id in ids }.forEach { it.read = read }
        return StubResponse(body = "ok", contentType = "text/plain")
    }

    private fun addresses(list: List<String>): JsonArray =
        buildJsonArray {
            list.forEach { add(address("", it)) }
        }

    private fun address(
        name: String,
        address: String,
    ) = buildJsonObject {
        put("Name", name)
        put("Address", address)
    }

    fun byId(id: String): Stored = messages.single { it.id == id }
}
