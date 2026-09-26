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

import az.petek.campaign.application.CampaignSource
import az.petek.campaign.domain.ActorExpressionParser
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.DefaultActorExpressionParser
import az.petek.campaign.domain.ValidationIssue
import com.charleskorn.kaml.EmptyYamlDocumentException
import com.charleskorn.kaml.MalformedYamlException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlTaggedNode
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Reads a campaign YAML file (schema: `docs/examples/company-portal.yaml`, docs/PLAN.md "Ssenari formatı") with kaml's node API,
 * so every problem carries the line it came from. The schema is strict: unknown keys are errors, not silently ignored,
 * because a misspelled `wait_for` or `assert` would otherwise turn into a test that checks nothing.
 *
 * Every failure (unreadable file, YAML syntax, schema mapping, actor grammar) is a [CampaignValidationException]
 * listing all problems found. Cross-field rules are checked afterwards by the campaign validator.
 *
 * Constructed by the composition root. [targetOverride] (`PETEK_TARGET` from `.env`) replaces `campaign.target`
 * and makes it optional in the file. [Campaign.sourceHash] is the lowercase hex SHA-256 of the file bytes.
 * Stateless and thread-safe; [load] reads the file with blocking I/O, so call it from `Dispatchers.IO` inside coroutines.
 */
class YamlCampaignSource(
    private val targetOverride: URI? = null,
    private val actorParser: ActorExpressionParser = DefaultActorExpressionParser(),
) : CampaignSource {
    override fun load(path: Path): Campaign {
        val bytes = read(path)
        val root = parse(decode(bytes, path))
        requireShallow(root)
        return CampaignYamlMapper(defaultName(path), targetOverride, actorParser).map(root, sha256(bytes))
    }

    /**
     * Refuses a document nested deeper than [MAX_NESTING] levels before the recursive mapper walks it. The walk here is
     * iterative, so the answer is the same whatever the stack depth: text a model wrote cannot make loading depend on
     * whether the JIT happened to shrink the parser's frames enough to survive (real campaigns nest about 8 levels).
     */
    private fun requireShallow(root: YamlNode) {
        val pending = ArrayDeque<Pair<YamlNode, Int>>()
        pending.addLast(root to 1)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (depth > MAX_NESTING) fail(node.location.line, "the YAML is nested too deeply (more than $MAX_NESTING levels)")
            when (node) {
                is YamlMap -> node.entries.values.forEach { pending.addLast(it to depth + 1) }
                is YamlList -> node.items.forEach { pending.addLast(it to depth + 1) }
                is YamlTaggedNode -> pending.addLast(node.innerNode to depth)
                else -> Unit
            }
        }
    }

    private fun read(path: Path): ByteArray =
        try {
            Files.readAllBytes(path)
        } catch (e: NoSuchFileException) {
            fail(null, "campaign file '$path' does not exist")
        } catch (e: IOException) {
            fail(null, "cannot read campaign file '$path': ${e.message ?: e.javaClass.simpleName}")
        }

    private fun decode(
        bytes: ByteArray,
        path: Path,
    ): String =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix(BYTE_ORDER_MARK)
        } catch (e: CharacterCodingException) {
            fail(null, "campaign file '$path' is not valid UTF-8")
        }

    /**
     * Valid YAML is parsed as is; only a file that fails to parse gets its actor flow lists quoted and a second try.
     * The repair is kept only when every line it touched is a step's `actor` key, otherwise the original error stands.
     */
    private fun parse(text: String): YamlNode =
        try {
            Yaml.default.parseToYamlNode(text)
        } catch (e: EmptyYamlDocumentException) {
            fail(null, "the campaign file is empty")
        } catch (e: MalformedYamlException) {
            parseRepaired(text, e)
        } catch (e: YamlException) {
            syntaxError(e)
        }

    private fun parseRepaired(
        text: String,
        original: MalformedYamlException,
    ): YamlNode {
        val repaired = quoteActorFlowLists(text)
        if (repaired == text) syntaxError(original)
        val root =
            try {
                Yaml.default.parseToYamlNode(repaired)
            } catch (retry: YamlException) {
                syntaxError(retry)
            }
        if (!onlyStepActorsChanged(changedLines(text, repaired), collectSourceLines(root))) syntaxError(original)
        return root
    }

    private fun syntaxError(e: YamlException): Nothing = fail(e.line, "YAML syntax error: ${e.message}")

    private fun fail(
        line: Int?,
        message: String,
    ): Nothing = throw CampaignValidationException(listOf(ValidationIssue(line, message)))

    private companion object {
        const val BYTE_ORDER_MARK = "\uFEFF"

        /** Deepest nesting a campaign may have; the real ones stay under 10. */
        const val MAX_NESTING = 64

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** `docs/examples/company-portal.yaml` -> `company-portal`. */
        fun defaultName(path: Path): String {
            val fileName = path.fileName?.toString().orEmpty()
            return fileName.substringBeforeLast('.').ifBlank { fileName }.ifBlank { "campaign" }
        }
    }
}
