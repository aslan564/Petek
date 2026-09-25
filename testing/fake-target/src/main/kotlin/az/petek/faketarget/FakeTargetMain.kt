package az.petek.faketarget

import java.util.concurrent.CountDownLatch

/**
 * `./gradlew :testing:fake-target:run`: a local fake KadroHR for demos and manual runs of Pətək.
 *
 * Environment (all optional): `PETEK_TEST_TOKEN` (default `dev-token`), `PETEK_MAIL_DOMAIN` (default
 * `test.kadrohr.com`), `FAKE_TARGET_PORT` (8080), `FAKE_TARGET_MAIL_PORT` (8025, so stop a real Mailpit first),
 * `FAKE_TARGET_PHONE_OTP` (true), `FAKE_TARGET_NOTIFICATION_DELAY_MS` (0), `FAKE_TARGET_BUGS`
 * (comma-separated [FakeBug] names) and `FAKE_TARGET_RACE_WINDOW_MS` (2000; widen it when LLM-driven agents should
 * hit [FakeBug.RACE_DOUBLE_APPROVE], since their clicks are seconds apart).
 */
fun main() {
    // Before the first logger exists: a quiet console configuration shipped with this module.
    System.setProperty("logback.configurationFile", "fake-target-logback.xml")
    val options = FakeTargetOptions.fromEnvironment(System.getenv())
    val server = FakeTargetServer(options.config).start(port = options.port, mailPort = options.mailPort)
    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            done.countDown()
        },
    )
    println(
        """
        |Fake KadroHR is running.
        |  Web:          http://localhost:${server.baseUrl.port}
        |  Mailpit API:  http://localhost:${server.mailpitUrl.port}
        |  X-Test-Token: ${options.config.testToken}
        |  Test domain:  @${options.config.testMailDomain}
        |  Phone OTP:    ${if (options.config.requirePhoneOtp) "required" else "off"}
        |  Bugs:         ${options.config.bugs
            .ifEmpty { null }
            ?.joinToString() ?: "none"}
        |Press Ctrl+C to stop.
        """.trimMargin(),
    )
    done.await()
}
