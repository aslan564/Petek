/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.store

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 hash; the fake never keeps a password in clear text. Compute outside the store lock (it is slow on purpose). */
internal class PasswordHash private constructor(
    private val salt: ByteArray,
    private val hash: ByteArray,
) {
    fun matches(password: String): Boolean = MessageDigest.isEqual(hash, derive(password, salt))

    override fun toString(): String = "PasswordHash(***)"

    companion object {
        private const val ITERATIONS = 10_000
        private const val KEY_BITS = 256
        private const val SALT_BYTES = 16

        fun of(
            password: String,
            random: SecureRandom,
        ): PasswordHash {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            return PasswordHash(salt, derive(password, salt))
        }

        private fun derive(
            password: String,
            salt: ByteArray,
        ): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}
