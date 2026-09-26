/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.infrastructure

import az.petek.ownership.domain.HostLocality
import az.petek.ownership.domain.LocalAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolves a host with the system resolver (hosts file included, so `kadro.test` → `127.0.0.1` counts as local) and
 * calls it local only when every address is [LocalAddresses.isLocal]: a name with one public address is somebody's
 * public site. A name that does not resolve is not local.
 */
class InetHostLocality : HostLocality {
    override suspend fun isLocal(host: String): Boolean =
        withContext(Dispatchers.IO) {
            val addresses =
                try {
                    InetAddress.getAllByName(host)
                } catch (_: UnknownHostException) {
                    emptyArray()
                }
            addresses.isNotEmpty() && addresses.all(LocalAddresses::isLocal)
        }
}
