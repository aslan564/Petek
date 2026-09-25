package az.petek.scenarios.infrastructure

import az.petek.campaign.application.CampaignSource
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.CampaignValidator
import az.petek.scenarios.domain.ScenarioCheck
import az.petek.scenarios.domain.ScenarioValidator
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
                    }
                ScenarioCheck(campaign, validator.validate(campaign, knownRunFunctions))
            } finally {
                Files.deleteIfExists(file)
                Files.deleteIfExists(directory)
            }
        }

    private fun createIn(parent: Path): Path {
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, PREFIX)
    }

    internal companion object {
        private const val PREFIX = "petek-scenario-"
        private const val FALLBACK = "scenario.yaml"
        private val UNSAFE = Regex("[^\\p{L}\\p{N}._-]")

        /** A plain file name: path parts are dropped and unusual characters replaced, so the text never escapes. */
        fun safeFileName(fileName: String): String {
            val base = fileName.substringAfterLast('/').substringAfterLast('\\').replace(UNSAFE, "_")
            return if (base.isBlank() || base.all { it == '.' }) FALLBACK else base
        }
    }
}
