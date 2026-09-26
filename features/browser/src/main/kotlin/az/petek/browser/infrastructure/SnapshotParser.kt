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

package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot

/** Size limits applied inside the page, so a huge page never produces a huge snapshot. */
internal data class SnapshotLimits(
    val maxElements: Int = 1000,
    val maxNameChars: Int = 80,
    val maxValueChars: Int = 200,
    val maxTextChars: Int = 6000,
) {
    /** The argument object handed to [BundledScripts.pageIndexer]. */
    fun asScriptArgument(): Map<String, Int> =
        mapOf(
            "maxElements" to maxElements,
            "maxNameChars" to maxNameChars,
            "maxValueChars" to maxValueChars,
            "maxTextChars" to maxTextChars,
        )
}

/**
 * Reads the result of [BundledScripts.pageIndexer] as Playwright deserializes it (maps, lists, `Number`s, strings)
 * into a [PageSnapshot]. Elements without a numeric ref are dropped rather than invented.
 */
internal object SnapshotParser {
    fun parse(raw: Any?): PageSnapshot {
        val root = raw as? Map<*, *> ?: throw BrowserActionException("the page snapshot script returned no data")
        val elements = (root["elements"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::element) }
        return PageSnapshot(
            url = root.text("url"),
            title = root.text("title"),
            elements = elements,
            visibleText = root.text("visibleText"),
        )
    }

    private fun element(fields: Map<*, *>): PageElement? {
        val ref = (fields["ref"] as? Number)?.toInt() ?: return null
        return PageElement(
            ref = ref,
            role = fields.text("role"),
            name = fields.text("name"),
            tag = fields.text("tag"),
            testId = fields["testId"] as? String,
            value = fields["value"] as? String,
            enabled = fields["enabled"] as? Boolean ?: true,
        )
    }

    private fun Map<*, *>.text(key: String): String = this[key] as? String ?: ""
}
