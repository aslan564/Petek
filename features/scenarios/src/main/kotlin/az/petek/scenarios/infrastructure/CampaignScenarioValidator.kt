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

package az.petek.scenarios.infrastructure

import az.petek.campaign.application.CampaignSource
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.CampaignValidator
import az.petek.campaign.domain.ValidationIssue
import az.petek.scenarios.domain.ScenarioCheck
import az.petek.scenarios.domain.ScenarioValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks scenario text with the same campaign loader ([source], e.g. `YamlCampaignSource` with the `.env` target
 * override) and [validator] a run uses, so a version the catalog accepts is a campaign `petek run` accepts.
 *
 * The loader reads files, so the text is written byte-exact (UTF-8) to a private temporary directory under
 * [workDirectory] (the system temp directory by default) as [ScenarioValidator.check]'s file name, and removed
 * afterwards. The loaded campaign's `sourceHash` therefore equals the version's SHA-256.
 * Constructed by the composition root with the run functions the agent feature knows.
 *
 * The text is untrusted (a model writes triage proposals), so a loader or validator that crashes on it (e.g. a stack
 * overflow on absurdly nested YAML) yields an issue, never an exception: invalid text is a result, as the port says.
 * Failing to write the temporary file is still an exception, because it says nothing about the text.
 */
class CampaignScenarioValidator(
    private val source: CampaignSource,
    private val validator: CampaignValidator,
    private val knownRunFunctions: Set<String>,
    private val workDirectory: Path? = null,
) : ScenarioValidator {
    override suspend fun check(
        yaml: String,
        fileName: String,
    ): ScenarioCheck =
        withContext(Dispatchers.IO) {
            val directory = if (workDirectory == null) Files.createTempDirectory(PREFIX) else createIn(workDirectory)
            val file = directory.resolve(safeFileName(fileName))
            try {
                Files.write(file, yaml.toByteArray(Charsets.UTF_8))
                val campaign =
                    try {
                        source.load(file)
                    } catch (e: CampaignValidationException) {
                        return@withContext ScenarioCheck(null, e.issues)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: StackOverflowError) {
                        return@withContext ScenarioCheck(null, listOf(ValidationIssue(null, TOO_DEEP)))
                    } catch (e: RuntimeException) {
                        return@withContext ScenarioCheck(null, listOf(crashed("campaign loader", e)))
                    }
                val issues =
                    try {
                        validator.validate(campaign, knownRunFunctions)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: StackOverflowError) {
                        listOf(ValidationIssue(null, TOO_DEEP))
                    } catch (e: RuntimeException) {
                        listOf(crashed("campaign validator", e))
                    }
                ScenarioCheck(campaign, issues)
            } finally {
                Files.deleteIfExists(file)
                Files.deleteIfExists(directory)
            }
        }

    private fun crashed(
        what: String,
        e: RuntimeException,
    ) = ValidationIssue(null, "the $what failed on this text: ${e::class.simpleName}: ${e.message ?: "no message"}")

    private fun createIn(parent: Path): Path {
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, PREFIX)
    }

    internal companion object {
        private const val PREFIX = "petek-scenario-"
        private const val TOO_DEEP = "the YAML is nested too deeply to be loaded"
        private const val FALLBACK = "scenario.yaml"
        private val UNSAFE = Regex("[^\\p{L}\\p{N}._-]")

        /** A plain file name: path parts are dropped and unusual characters replaced, so the text never escapes. */
        fun safeFileName(fileName: String): String {
            val base = fileName.substringAfterLast('/').substringAfterLast('\\').replace(UNSAFE, "_")
            return if (base.isBlank() || base.all { it == '.' }) FALLBACK else base
        }
    }
}
