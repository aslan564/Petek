/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EnvFileTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads key value pairs and skips blank lines and comments`() {
        val values =
            EnvFile.parse(
                """
                # Target under test
                PETEK_TARGET=https://staging.kadrohr.com

                   # indented comment
                PETEK_LLM_CONCURRENCY=6
                """.trimIndent(),
            )

        values shouldBe mapOf("PETEK_TARGET" to "https://staging.kadrohr.com", "PETEK_LLM_CONCURRENCY" to "6")
    }

    @Test
    fun `an empty value is kept as an empty string`() {
        EnvFile.parse("PETEK_TEST_TOKEN=") shouldBe mapOf("PETEK_TEST_TOKEN" to "")
    }

    @Test
    fun `whitespace around keys and unquoted values is dropped`() {
        EnvFile.parse("  PETEK_MAIL_DOMAIN =  test.kadrohr.com  ") shouldBe mapOf("PETEK_MAIL_DOMAIN" to "test.kadrohr.com")
    }

    @Test
    fun `a trailing comment after an unquoted value is removed`() {
        EnvFile.parse("PETEK_DB=evidence/petek.db   # the evidence database") shouldBe mapOf("PETEK_DB" to "evidence/petek.db")
    }

    @Test
    fun `a hash inside an unquoted value is part of the value`() {
        EnvFile.parse("PETEK_TEST_TOKEN=abc#123") shouldBe mapOf("PETEK_TEST_TOKEN" to "abc#123")
    }

    @Test
    fun `a comment right after the equals sign leaves the value empty`() {
        val values =
            EnvFile.parse(
                "PETEK_IDENTITY_SECRET=   # leave empty to use ~/.petek/identity.secret\n" +
                    "PETEK_TEST_TOKEN=\t# tab before the comment\n",
            )

        values shouldBe mapOf("PETEK_IDENTITY_SECRET" to "", "PETEK_TEST_TOKEN" to "")
    }

    @Test
    fun `a hash directly after the equals sign starts the value`() {
        EnvFile.parse("PETEK_TEST_TOKEN=#abc # a token that starts with a hash") shouldBe mapOf("PETEK_TEST_TOKEN" to "#abc")
    }

    @Test
    fun `a quoted value may follow whitespace after the equals sign`() {
        EnvFile.parse("""NAME=   "Pətək # Test"   # comment""") shouldBe mapOf("NAME" to "Pətək # Test")
    }

    @Test
    fun `text before the equals sign that may hold a value is not echoed`() {
        val error = shouldThrow<EnvFileException> { EnvFile.parse("PETEK_TEST_TOKEN: s3cr3t=value") }

        error.problems shouldContainExactly listOf("line 1: the text before '=' is not a valid variable name")
        error.message shouldNotContain "s3cr3t"
    }

    @Test
    fun `double quotes keep spaces and hashes and understand escapes`() {
        val values = EnvFile.parse("""GREETING="a # b\n\t\"c\" \\ d"   # comment""")

        values["GREETING"] shouldBe "a # b\n\t\"c\" \\ d"
    }

    @Test
    fun `single quotes are literal`() {
        EnvFile.parse("""RAW='a # b\n c'""") shouldBe mapOf("RAW" to """a # b\n c""")
    }

    @Test
    fun `an export prefix is accepted`() {
        EnvFile.parse("export PETEK_TARGET=http://localhost:8080") shouldBe mapOf("PETEK_TARGET" to "http://localhost:8080")
        EnvFile.parse("export\t PETEK_DB=a.db\nexported=1") shouldBe mapOf("PETEK_DB" to "a.db", "exported" to "1")
    }

    @Test
    fun `windows line endings and a byte order mark are tolerated`() {
        EnvFile.parse("﻿A=1\r\nB=2\r\n") shouldBe mapOf("A" to "1", "B" to "2")
    }

    @Test
    fun `the last assignment of a key wins`() {
        EnvFile.parse("A=1\nA=2") shouldBe mapOf("A" to "2")
    }

    @Test
    fun `every malformed line is reported with its number and without values`() {
        val error =
            shouldThrow<EnvFileException> {
                EnvFile.parse(
                    """
                    GOOD=1
                    no equals sign here
                    1BAD=x
                    TOKEN="super-secret-value
                    OTHER='closed' trailing-secret
                    """.trimIndent(),
                )
            }

        error.problems shouldContainExactly
            listOf(
                "line 2: expected KEY=VALUE",
                "line 3: '1BAD' is not a valid variable name",
                "line 4: TOKEN: the double-quoted value is not closed",
                "line 5: OTHER: unexpected text after the closing quote",
            )
        error.message shouldNotContain "super-secret-value"
        error.message shouldNotContain "trailing-secret"
    }

    @Test
    fun `a missing file is simply empty`() {
        EnvFile.load(dir.resolve("absent.env")).shouldBeEmpty()
    }

    @Test
    fun `a file is read as UTF-8`() {
        val file = dir.resolve(".env").also { Files.writeString(it, "NAME=Pətək Test MMC\n") }

        EnvFile.load(file) shouldBe mapOf("NAME" to "Pətək Test MMC")
    }

    @Test
    fun `a file that is not UTF-8 is rejected with its name`() {
        val file = dir.resolve("latin1.env").also { Files.write(it, byteArrayOf('A'.code.toByte(), '='.code.toByte(), 0xE9.toByte())) }

        val error = shouldThrow<EnvFileException> { EnvFile.load(file) }

        error.message shouldContain "latin1.env"
        error.problems shouldContainExactly listOf("the file is not valid UTF-8")
    }
}
