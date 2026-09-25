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
import com.charleskorn.kaml.YamlNode
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
 * Reads a campaign YAML file (schema: `scenarios/kadrohr.yaml`, docs/PLAN.md "Ssenari formatı") with kaml's node API,
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
        return CampaignYamlMapper(defaultName(path), targetOverride, actorParser).map(root, sha256(bytes))
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

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** `scenarios/kadrohr.yaml` -> `kadrohr`. */
        fun defaultName(path: Path): String {
            val fileName = path.fileName?.toString().orEmpty()
            return fileName.substringBeforeLast('.').ifBlank { fileName }.ifBlank { "campaign" }
        }
    }
}
