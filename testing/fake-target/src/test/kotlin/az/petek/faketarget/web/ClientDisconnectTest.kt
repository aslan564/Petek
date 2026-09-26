/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.web

import io.kotest.matchers.shouldBe
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedWriteChannelException
import org.junit.jupiter.api.Test
import java.io.IOException

class ClientDisconnectTest {
    @Test
    fun `a closed write channel is a client that left, also deep in the cause chain`() {
        ClosedWriteChannelException(IOException("Broken pipe")).isClientDisconnect() shouldBe true
        ClosedWriteChannelException(null).isClientDisconnect() shouldBe true
        ChannelWriteException("Cannot write to channel", IOException("Broken pipe")).isClientDisconnect() shouldBe true
        IllegalStateException("wrapped", RuntimeException("again", IOException("Broken pipe"))).isClientDisconnect() shouldBe true
    }

    @Test
    fun `other failures are still errors`() {
        IllegalStateException("database locked").isClientDisconnect() shouldBe false
        IOException("No space left on device").isClientDisconnect() shouldBe false
        RuntimeException("boom", NullPointerException()).isClientDisconnect() shouldBe false
    }
}
