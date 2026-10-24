package com.v2ray.ang.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.easytier.plugin.EasyTierCidr
import com.easytier.plugin.EasyTierRoutePlanner
import com.v2ray.ang.AppConfig
import java.net.Inet4Address

/**
 * Collects the directly connected LAN subnets of the device.
 *
 * These are the networks that host local services such as LocalSend or KDE
 * Connect.  They must never be captured by the EasyTier routing rules,
 * otherwise replies to LAN peers are pulled into the VPN tunnel and the
 * services appear unreachable.
 */
object NetworkRouteUtil {

    /**
     * Returns IPv4 LAN CIDRs for Wi-Fi, Ethernet and USB networks.
     *
     * Cellular networks are skipped on purpose: their point-to-point /30 is
     * not a LAN, and including it could collide with the EasyTier virtual
     * subnet.  The VPN's own interface and loopback are also skipped.
     */
    @Suppress("DEPRECATION") // getAllNetworks is the only synchronous way to see Wi-Fi + Ethernet at once. Replace with a NetworkCallback cache when minSdk >= 31.
    fun collectLocalLanCidrs(context: Context): List<String> {
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()

        val result = LinkedHashSet<String>()
        try {
            for (network in connectivity.allNetworks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!isPhysicalLan(capabilities)) continue

                val linkProperties = connectivity.getLinkProperties(network) ?: continue
                for (linkAddress in linkProperties.linkAddresses) {
                    val address = linkAddress.address
                    if (address !is Inet4Address) continue
                    val prefix = linkAddress.prefixLength
                    if (prefix !in 1..31) continue
                    val cidr = EasyTierCidr.parse("${address.hostAddress}/$prefix")?.toString()
                    // Only private/special-use local subnets may become direct
                    // rules.  A public Wi-Fi subnet must not bypass the proxy.
                    if (cidr != null && EasyTierRoutePlanner.isSafeMeshCidr(cidr)) {
                        result.add(cidr)
                    } else if (cidr != null) {
                        LogUtil.w(
                            AppConfig.TAG,
                            "NetworkRouteUtil: ignoring non-private local subnet $cidr"
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            LogUtil.w(AppConfig.TAG, "NetworkRouteUtil: failed to collect local LAN CIDRs", e)
        }
        return result.toList()
    }

    /**
     * Returns /32 host routes for private DNS servers of the physical LANs.
     *
     * When the VPN bypasses the LAN, a private resolver (usually the router)
     * would otherwise be queried directly.  Capturing it with a host route
     * lets Xray's dns-out module handle the query, matching the behaviour of
     * the old broad-private-route builds without capturing LocalSend/KDE
     * Connect traffic.
     */
    fun collectPrivateDnsHostRoutes(context: Context): List<String> {
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()

        val result = LinkedHashSet<String>()
        try {
            @Suppress("DEPRECATION")
            for (network in connectivity.allNetworks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!isPhysicalLan(capabilities)) continue

                val linkProperties = connectivity.getLinkProperties(network) ?: continue
                for (dns in linkProperties.dnsServers) {
                    if (dns !is Inet4Address) continue
                    val cidr = EasyTierCidr.parse("${dns.hostAddress}/32")?.toString() ?: continue
                    if (EasyTierRoutePlanner.isSafeMeshCidr(cidr)) result.add(cidr)
                }
            }
        } catch (e: Throwable) {
            LogUtil.w(AppConfig.TAG, "NetworkRouteUtil: failed to collect private DNS routes", e)
        }
        return result.toList()
    }

    private fun isPhysicalLan(capabilities: NetworkCapabilities): Boolean {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)
        ) {
            return true
        }
        return false
    }
}