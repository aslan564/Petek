/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress

class LocalAddressesTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "127.0.0.1", "127.8.9.10", "::1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.20",
            "169.254.1.1", "fe80::1", "fd12:3456::1", "fc00::1",
        ],
    )
    fun `loopback, private and link-local addresses are local`(literal: String) {
        LocalAddresses.isLocal(InetAddress.getByName(literal)) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["8.8.8.8", "172.32.0.1", "172.15.255.255", "100.64.0.1", "2001:4860:4860::8888", "192.169.0.1"])
    fun `public and carrier-shared addresses are not local`(literal: String) {
        LocalAddresses.isLocal(InetAddress.getByName(literal)) shouldBe false
    }
}
