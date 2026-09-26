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

import az.petek.campaign.domain.SourceLines
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlTaggedNode

/** Path of [key] inside the map at [path] (see [SourceLines] for the path format). */
internal fun childPath(
    path: String,
    key: String,
): String = if (path == SourceLines.ROOT) key else "$path.$key"

/** How a path appears in messages. */
internal fun displayPath(path: String): String = if (path == SourceLines.ROOT) "the campaign file" else "'$path'"

/** Tags are transparent and an explicit null (`key: ~` or `key:`) counts as absent. */
internal fun YamlNode?.plain(): YamlNode? =
    when (this) {
        null, is YamlNull -> null
        is YamlTaggedNode -> innerNode.plain()
        else -> this
    }

/** Path -> line of every map key and list item, so issues found later (also by the validator) point at the file. */
internal fun collectSourceLines(root: YamlNode): SourceLines {
    val lines = mutableMapOf(SourceLines.ROOT to root.location.line)

    fun visit(
        node: YamlNode,
        path: String,
    ) {
        when (node) {
            is YamlMap -> {
                node.entries.forEach { (key, value) ->
                    val keyPath = childPath(path, key.content)
                    lines[keyPath] = key.location.line
                    visit(value, keyPath)
                }
            }

            is YamlList -> {
                node.items.forEachIndexed { index, item ->
                    val itemPath = "$path[$index]"
                    lines[itemPath] = item.location.line
                    visit(item, itemPath)
                }
            }

            is YamlTaggedNode -> {
                visit(node.innerNode, path)
            }

            else -> {
                return
            }
        }
    }
    visit(root, SourceLines.ROOT)
    return SourceLines(lines)
}
