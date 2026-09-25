/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
