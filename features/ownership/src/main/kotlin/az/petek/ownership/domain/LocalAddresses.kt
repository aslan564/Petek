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
