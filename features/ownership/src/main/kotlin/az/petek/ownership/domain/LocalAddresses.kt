/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.domain

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Addresses that cannot belong to somebody else's public site, so a target there needs no ownership proof (the owner's
 * decision of 2026-09-26): loopback, the unspecified address, the private IPv4 ranges (10/8, 172.16/12, 192.168/16),
 * IPv6 unique-local addresses (fc00::/7) and link-local addresses. Shared carrier address space (100.64/10) is not
 * among them: other customers of the same carrier can sit there.
 */
object LocalAddresses {
    fun isLocal(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            isUniqueLocal(address)

    private fun isUniqueLocal(address: InetAddress): Boolean =
        address is Inet6Address && (address.address[0].toInt() and UNIQUE_LOCAL_MASK) == UNIQUE_LOCAL_PREFIX

    private const val UNIQUE_LOCAL_MASK = 0xFE
    private const val UNIQUE_LOCAL_PREFIX = 0xFC
}
