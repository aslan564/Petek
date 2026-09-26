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
