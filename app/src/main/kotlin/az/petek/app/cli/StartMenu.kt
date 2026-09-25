package az.petek.app.cli

import az.petek.app.demo.DemoTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory

/**
 * What `petek` offers when started without a command (e.g. IntelliJ's run icon next to `main`): one place to choose
 * what to do. Each choice runs a normal `petek` command line through [execute]; the menu returns after `0` or when
 * there is no more input (then it prints the command-line guide instead, so non-interactive starts stay useful).
 */
internal class StartMenu(
    private val runtime: CliRuntime,
    private val execute: suspend (List<String>) -> Int,
    /** Shown instead of the menu's first prompt when nothing can be typed (a script or CI started `petek`). */
    private val noInput: suspend () -> Unit = {},
) {
    suspend fun run(): Int {
        var first = true
        while (true) {
            runtime.output(MENU)
            val line = runtime.input()
            if (line == null) {
                if (first) noInput()
                return ExitCodes.OK
            }
            first = false
            val choice = line.trim().ifEmpty { "1" }
            if (choice == "0" || choice.equals("q", ignoreCase = true)) return ExitCodes.OK
            val action = actions[choice]
            if (action == null) {
                runtime.output("Belə seçim yoxdur: '$choice'. 0–6 arasında bir rəqəm yazın.")
                continue
            }
            val status = action()
            runtime.output(if (status == ExitCodes.OK) "\n✓ Hazırdır." else "\n✗ Bitdi (kod $status). Səbəb yuxarıda yazılıb.")
        }
    }

    private val actions: Map<String, suspend () -> Int> =
        mapOf(
            "1" to { withDemoTarget { env -> execute(listOf("--env-file", env, "run", CAMPAIGN, "--agents", "12", "--headful")) } },
            "2" to { withDemoTarget { env -> execute(listOf("--env-file", env, "doctor")) } },
            "3" to { withDemoTarget { env -> execute(listOf("--env-file", env, "plan", CAMPAIGN)) } },
            "4" to { withDemoTarget { env -> execute(listOf("--env-file", env, "smoke", "--url", "https://kadrohr.com")) } },
            "5" to { openLatestReport() },
            "6" to {
                runtime.output(
                    "Veb panel (Təlimat, Kəşfiyyat, Ssenarilər, Orkestrator, Agentlər, Hesabatlar) hazırlanır; hazır olanda buradan açılacaq.",
                )
                ExitCodes.OK
            },
        )

    private suspend fun withDemoTarget(block: suspend (String) -> Int): Int {
        if (!runtime.workingDirectory.resolve(CAMPAIGN).exists()) {
            runtime.output("$CAMPAIGN tapılmadı. Pətək layihəsinin kök qovluğundan işə salın.")
            return ExitCodes.CONFIG_OR_ABORTED
        }
        return DemoTarget(runtime.workingDirectory).use { demo ->
            val env = demo.start()
            runtime.output("Lokal test saytı hazırdır (konfiqurasiya: ${runtime.workingDirectory.relativize(env)}).")
            block(env.toString())
        }
    }

    private fun openLatestReport(): Int {
        val evidence = runtime.workingDirectory.resolve("evidence")
        val latest =
            if (evidence.isDirectory()) {
                Files.list(evidence).use { runs ->
                    runs
                        .map { it.resolve("report").resolve("index.html") }
                        .filter { it.exists() }
                        .toList()
                        .maxByOrNull { it.getLastModifiedTime() }
                }
            } else {
                null
            }
        if (latest == null) {
            runtime.output("Hələ hesabat yoxdur. Əvvəlcə 1-ci seçimlə bir run edin.")
            return ExitCodes.FAILURE
        }
        runtime.output("Hesabat: $latest")
        if (!runtime.openInBrowser(latest.toString())) runtime.output("Brauzer açılmadı; faylı özünüz açın.")
        return ExitCodes.OK
    }

    private companion object {
        const val CAMPAIGN = "scenarios/kadrohr.yaml"

        val MENU =
            """

            ═══════════════════════════════════════════════════════════════════
             Pətək — nə etmək istəyirsiniz?
            ═══════════════════════════════════════════════════════════════════
              1. Demo: lokal test saytı + 12 tester canlı (Chromium pəncərələri)
              2. Yoxlama (doctor): sayt, brauzer, poçt, test API, Claude
              3. Kimlik planı: kampaniyanın testerlərini göstər (heç nə icra etmir)
              4. kadrohr.com-a baxış (smoke): yalnız açır, heç nə yazmır
              5. Son hesabatı brauzerdə aç
              6. Veb panel (hazırlanır)
              0. Çıxış
            Seçim [1]:
            """.trimIndent()
    }
}

/** Relative path for display; falls back to the absolute path when [other] is elsewhere. */
private fun Path.relativize(other: Path): Path = runCatching { this.relativize(other) }.getOrDefault(other)
