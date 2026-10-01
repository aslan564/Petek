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

package az.petek.evidence.infrastructure

import az.petek.core.ids.ArtifactId
import az.petek.evidence.domain.LookAnchor
import az.petek.evidence.domain.LookBox
import az.petek.evidence.domain.LookFrame
import az.petek.evidence.domain.LookFrameKind
import az.petek.evidence.domain.LookMask
import az.petek.evidence.domain.LookMaskReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JSON columns of `page_look`, short-keyed so a look with hundreds of masks stays small, e.g. a frame:
 * `{"a":"art_1","k":"MAIN","w":375,"h":2310,"m":[{"x":0,"y":12,"w":120,"h":18,"r":"RUN_TEXT","s":"tester_name"}]}`.
 * Enums by name, so new reasons never break older rows.
 */
internal object LookJson {
    fun strings(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    fun strings(text: String): List<String> = Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }

    fun frames(frames: List<LookFrame>): String =
        JsonArray(
            frames.map { frame ->
                JsonObject(
                    mapOf(
                        "a" to JsonPrimitive(frame.artifactId.value),
                        "k" to JsonPrimitive(frame.kind.name),
                        "w" to JsonPrimitive(frame.width),
                        "h" to JsonPrimitive(frame.height),
                        "m" to JsonArray(frame.masks.map(::mask)),
                    ),
                )
            },
        ).toString()

    fun frames(text: String): List<LookFrame> =
        Json.parseToJsonElement(text).jsonArray.map { element ->
            val frame = element.jsonObject
            LookFrame(
                artifactId = ArtifactId(frame.getValue("a").jsonPrimitive.content),
                kind = LookFrameKind.valueOf(frame.getValue("k").jsonPrimitive.content),
                width = frame.getValue("w").jsonPrimitive.int,
                height = frame.getValue("h").jsonPrimitive.int,
                masks = frame.getValue("m").jsonArray.map(::mask),
            )
        }

    fun anchors(anchors: List<LookAnchor>): String =
        JsonArray(anchors.map { JsonObject(box(it.box) + ("s" to JsonPrimitive(it.selector))) }).toString()

    fun anchors(text: String): List<LookAnchor> =
        Json.parseToJsonElement(text).jsonArray.map { element ->
            LookAnchor(
                element.jsonObject
                    .getValue("s")
                    .jsonPrimitive.content,
                box(element.jsonObject),
            )
        }

    private fun mask(mask: LookMask): JsonElement =
        JsonObject(box(mask.box) + mapOf("r" to JsonPrimitive(mask.reason.name), "s" to JsonPrimitive(mask.source)))

    private fun mask(element: JsonElement): LookMask {
        val mask = element.jsonObject
        return LookMask(
            box(mask),
            LookMaskReason.valueOf(mask.getValue("r").jsonPrimitive.content),
            mask.getValue("s").jsonPrimitive.content,
        )
    }

    private fun box(box: LookBox): Map<String, JsonElement> =
        mapOf("x" to JsonPrimitive(box.x), "y" to JsonPrimitive(box.y), "w" to JsonPrimitive(box.width), "h" to JsonPrimitive(box.height))

    private fun box(json: JsonObject): LookBox =
        LookBox(
            json.getValue("x").jsonPrimitive.int,
            json.getValue("y").jsonPrimitive.int,
            json.getValue("w").jsonPrimitive.int,
            json.getValue("h").jsonPrimitive.int,
        )
}
