package az.petek.scenarios.infrastructure

import az.petek.scenarios.domain.EvidenceRef
import az.petek.scenarios.domain.EvidenceRefType
import az.petek.scenarios.domain.YamlEdit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** JSON encodings of list-valued columns: string arrays, `[{"type","id"}]` evidence refs and `[{"find","replace"}]` edits. */
internal object JsonColumns {
    fun strings(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    fun strings(text: String): List<String> = Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }

    fun refs(values: List<EvidenceRef>): String =
        JsonArray(
            values.map {
                buildJsonObject {
                    put(TYPE, it.type.name)
                    put(ID, it.id)
                }
            },
        ).toString()

    fun refs(text: String): List<EvidenceRef> =
        Json.parseToJsonElement(text).jsonArray.map { element ->
            val fields = element.jsonObject
            EvidenceRef(
                enumValueOf<EvidenceRefType>(fields.getValue(TYPE).jsonPrimitive.content),
                fields.getValue(ID).jsonPrimitive.content,
            )
        }

    fun edits(values: List<YamlEdit>): String =
        JsonArray(
            values.map {
                buildJsonObject {
                    put(FIND, it.find)
                    put(REPLACE, it.replace)
                }
            },
        ).toString()

    fun edits(text: String): List<YamlEdit> =
        Json.parseToJsonElement(text).jsonArray.map { element ->
            val fields = element.jsonObject
            YamlEdit(fields.getValue(FIND).jsonPrimitive.content, fields.getValue(REPLACE).jsonPrimitive.content)
        }

    private const val TYPE = "type"
    private const val ID = "id"
    private const val FIND = "find"
    private const val REPLACE = "replace"
}
