/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.domain

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ownership tokens as the first 128 bits of HMAC-SHA256 over `petek-ownership-v1:<host>` under the identity secret.
 * The same secret (`PETEK_IDENTITY_SECRET` shared by a team or CI) gives the same token on every machine, so one
 * published proof serves them all; a machine with its own secret has its own token, and the file or the TXT record may
 * carry one line per token. The label keeps these digests apart from the passwords derived from the same secret.
 */
class HmacOwnershipTokens(
    secret: ByteArray,
) : OwnershipTokens {
    private val key: SecretKeySpec

    init {
        require(secret.isNotEmpty()) { "The ownership secret must not be empty" }
        key = SecretKeySpec(secret.copyOf(), ALGORITHM)
    }

    override fun tokenFor(host: String): OwnershipToken {
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        val digest = mac.doFinal("$LABEL$host".toByteArray(Charsets.UTF_8))
        return OwnershipToken(HexFormat.of().formatHex(digest, 0, TOKEN_BYTES))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val LABEL = "petek-ownership-v1:"
        const val TOKEN_BYTES = 16
    }
}
