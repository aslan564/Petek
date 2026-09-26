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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Sets one variable in a `.env` file (the panel's "Hesablar" form, rule 10: secrets live in `.env`, never in the
 * database): the line of [key] is replaced, or appended when there is none; every other line stays as it was. The
 * value is written double-quoted, so `#` and spaces survive [EnvFile]; the file is replaced atomically and kept
 * `rw-------`.
 */
object EnvFileWriter {
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun set(
        file: Path,
        key: String,
        value: String,
    ) {
        require(KEY.matches(key)) { "not a variable name: $key" }
        require(value.none(Char::isISOControl)) { "a .env value cannot contain a line break or another control character" }
        val line = "$key=\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val lines = if (Files.exists(file)) Files.readAllLines(file) else emptyList()
        val pattern = Regex("^\\s*(export\\s+)?${Regex.escape(key)}\\s*=.*")
        val replaced = lines.map { if (pattern.matches(it)) line else it }
        val next = if (replaced == lines && lines.none { pattern.matches(it) }) lines + line else replaced
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val temporary = Files.createTempFile(file.toAbsolutePath().parent, ".env.", ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")) }
            Files.writeString(temporary, next.joinToString("\n", postfix = "\n"))
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
