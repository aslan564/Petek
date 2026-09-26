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

package az.petek.app.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class IdentitySecretFileTest {
    @TempDir
    lateinit var home: Path

    private val directory: Path get() = home.resolve(".petek")

    @Test
    fun `the first use creates a random 32 byte secret readable by the owner only`() {
        val source = IdentitySecretFile(directory)

        val secret = source.secret()

        secret.reveal() shouldMatch Regex("[0-9a-f]{64}")
        Files.readString(source.path).trim() shouldBe secret.reveal()
        PosixFilePermissions.toString(Files.getPosixFilePermissions(source.path)) shouldBe "rw-------"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)) shouldBe "rwx------"
    }

    @Test
    fun `later uses read the same secret`() {
        val first = IdentitySecretFile(directory).secret()

        IdentitySecretFile(directory).secret() shouldBe first
    }

    @Test
    fun `two machines get different secrets`() {
        IdentitySecretFile(home.resolve("one")).secret().reveal() shouldBe IdentitySecretFile(home.resolve("one")).secret().reveal()
        (IdentitySecretFile(home.resolve("one")).secret() == IdentitySecretFile(home.resolve("two")).secret()) shouldBe false
    }

    @Test
    fun `a secret file others can read is tightened`() {
        val source = IdentitySecretFile(directory)
        source.secret()
        Files.setPosixFilePermissions(source.path, PosixFilePermissions.fromString("rw-r--r--"))

        source.secret()

        PosixFilePermissions.toString(Files.getPosixFilePermissions(source.path)) shouldBe "rw-------"
    }

    @Test
    fun `an empty secret file is an error that explains the way out`() {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(IdentitySecretFile.FILE_NAME), "  \n")

        val error = shouldThrow<ConfigException> { IdentitySecretFile(directory).secret() }

        error.message shouldContain "is empty"
        error.message shouldContain "PETEK_IDENTITY_SECRET"
    }

    @Test
    fun `concurrent first uses agree on one secret`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val secrets = (1..16).map { pool.submit(Callable { IdentitySecretFile(directory).secret() }) }.map { it.get() }

            secrets.toSet() shouldHaveSize 1
            Files.list(directory).use { files -> files.toList() } shouldHaveSize 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the default directory follows the user home system property`() {
        val original = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            IdentitySecretFile.defaultDirectory() shouldBe home.resolve(".petek")

            val loaded =
                ConfigLoader(emptyMap(), home, IdentitySecretFile(IdentitySecretFile.defaultDirectory()))
                    .fromValues(mapOf("PETEK_TARGET" to "https://staging.portal.example"))

            Files.readString(home.resolve(".petek/identity.secret")).trim() shouldBe loaded.identitySecret.reveal()
        } finally {
            System.setProperty("user.home", original)
        }
    }
}
