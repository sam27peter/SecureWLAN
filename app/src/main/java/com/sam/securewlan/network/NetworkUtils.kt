
package com.sam.securewlan.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: return null

            val addresses = mutableListOf<Pair<Int, String>>()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (!networkInterface.isUp ||
                    networkInterface.isLoopback
                ) {
                    continue
                }

                val name = networkInterface.name.lowercase()

                // Prefer Wi-Fi interfaces over other interfaces.
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("wifi") -> 0
                    else -> 1
                }

                val addressesForInterface =
                    networkInterface.inetAddresses

                while (addressesForInterface.hasMoreElements()) {
                    val address = addressesForInterface.nextElement()

                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        addresses.add(
                            priority to address.hostAddress
                        )
                    }
                }
            }

            addresses
                .sortedBy { it.first }
                .firstOrNull()
                ?.second
        } catch (e: Exception) {
            null
        }
    }
}
