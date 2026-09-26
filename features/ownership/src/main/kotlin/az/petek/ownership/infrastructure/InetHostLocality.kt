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
