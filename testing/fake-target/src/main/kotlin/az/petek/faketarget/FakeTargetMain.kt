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

package az.petek.faketarget

import java.util.concurrent.CountDownLatch

/**
 * `./gradlew :testing:fake-target:run`: a local fake target for demos and manual runs of Pətək.
 *
 * Environment (all optional): `PETEK_TEST_TOKEN` (default `dev-token`), `PETEK_MAIL_DOMAIN` (default
 * `test.portal.example`), `FAKE_TARGET_PORT` (18080), `FAKE_TARGET_MAIL_PORT` (18025; different from Mailpit's 8025 so both can run),
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
        |Fake target is running.
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
