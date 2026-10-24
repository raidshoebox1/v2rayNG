package com.easytier.plugin

import java.net.InetAddress

/**
 * A single IPv4 or IPv6 CIDR block.
 *
 * The address is stored in network byte order and always has its host bits
 * cleared.  The class is deliberately dependency-free so it can be unit
 * tested on the JVM without Android.
 */
class EasyTierCidr(
    address: ByteArray,
    val prefixLength: Int
) {
    init {
        require(address.size == IPV4_BYTES || address.size == IPV6_BYTES) {
            "CIDR address must be 4 or 16 bytes"
        }
        require(prefixLength in 0..(address.size * 8)) {
            "Invalid prefix length $prefixLength for ${address.size} byte address"
        }
    }

    /** Network address with host bits cleared. */
    val address: ByteArray = mask(address, prefixLength)

    val isIpv6: Boolean get() = address.size == IPV6_BYTES

    /** Number of address bits. */
    val bitCount: Int get() = address.size * 8

    /** Whether this block fully contains [other]. */
    fun contains(other: EasyTierCidr): Boolean {
        if (address.size != other.address.size) return false
        if (prefixLength > other.prefixLength) return false
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) {
            if (address[i] != other.address[i]) return false
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (address[fullBytes].toInt() and mask) == (other.address[fullBytes].toInt() and mask)
    }

    /** Whether the two blocks share at least one address. */
    fun intersects(other: EasyTierCidr): Boolean =
        contains(other) || other.contains(this)

    /**
     * Split this block into two blocks with one more prefix bit.
     * The caller must ensure [prefixLength] is smaller than [bitCount].
     */
    fun split(): Pair<EasyTierCidr, EasyTierCidr> {
        require(prefixLength < bitCount) { "Cannot split a /$prefixLength block" }
        val nextPrefix = prefixLength + 1
        val first = EasyTierCidr(address, nextPrefix)
        val secondBytes = address.copyOf()
        val bitIndex = prefixLength
        secondBytes[bitIndex / 8] =
            (secondBytes[bitIndex / 8].toInt() or (0x80 ushr (bitIndex % 8))).toByte()
        return first to EasyTierCidr(secondBytes, nextPrefix)
    }

    override fun toString(): String {
        val host = if (isIpv6) {
            formatIpv6(address)
        } else {
            InetAddress.getByAddress(address).hostAddress ?: return "$prefixLength"
        }
        return "$host/$prefixLength"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EasyTierCidr) return false
        return prefixLength == other.prefixLength && address.contentEquals(other.address)
    }

    override fun hashCode(): Int = 31 * address.contentHashCode() + prefixLength

    companion object {
        private const val IPV4_BYTES = 4
        private const val IPV6_BYTES = 16

        /**
         * Parse a CIDR or a bare address.  Bare addresses default to /32 (IPv4)
         * or /128 (IPv6).  Host names are never resolved.
         */
        fun parse(value: String): EasyTierCidr? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null

            val slash = trimmed.indexOf('/')
            val addressPart = if (slash >= 0) trimmed.substring(0, slash) else trimmed
            if (addressPart.isEmpty()) return null

            val bytes = parseAddress(addressPart) ?: return null
            val maxPrefix = bytes.size * 8
            val prefix = if (slash >= 0) {
                trimmed.substring(slash + 1).toIntOrNull() ?: return null
            } else {
                maxPrefix
            }
            if (prefix !in 0..maxPrefix) return null
            return EasyTierCidr(bytes, prefix)
        }

        private fun parseAddress(value: String): ByteArray? {
            return if (value.contains(':')) {
                // InetAddress only treats this as a literal because it contains a
                // colon; it never performs a DNS lookup for such strings.
                try {
                    InetAddress.getByName(value).address.takeIf { it.size == IPV6_BYTES }
                } catch (e: Throwable) {
                    null
                }
            } else {
                parseIpv4(value)
            }
        }

        private fun parseIpv4(value: String): ByteArray? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val octet = parts[i].toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                bytes[i] = octet.toByte()
            }
            return bytes
        }

        private fun mask(bytes: ByteArray, prefix: Int): ByteArray {
            val result = bytes.copyOf()
            var remaining = prefix
            for (i in result.indices) {
                when {
                    remaining >= 8 -> remaining -= 8
                    remaining <= 0 -> result[i] = 0
                    else -> {
                        val mask = (0xFF shl (8 - remaining)) and 0xFF
                        result[i] = (result[i].toInt() and mask).toByte()
                        remaining = 0
                    }
                }
            }
            return result
        }

        /** Canonical (RFC 5952 style) IPv6 text with the longest zero run compressed. */
        private fun formatIpv6(bytes: ByteArray): String {
            val groups = IntArray(8) { index ->
                ((bytes[index * 2].toInt() and 0xFF) shl 8) or
                    (bytes[index * 2 + 1].toInt() and 0xFF)
            }
            var bestStart = -1
            var bestLength = 0
            var index = 0
            while (index < groups.size) {
                if (groups[index] != 0) {
                    index++
                    continue
                }
                val start = index
                while (index < groups.size && groups[index] == 0) index++
                val length = index - start
                if (length >= 2 && length > bestLength) {
                    bestStart = start
                    bestLength = length
                }
            }

            val builder = StringBuilder()
            if (bestStart < 0) {
                groups.forEachIndexed { i, group ->
                    if (i > 0) builder.append(':')
                    builder.append(group.toString(16))
                }
                return builder.toString()
            }

            for (i in 0 until bestStart) {
                if (i > 0) builder.append(':')
                builder.append(groups[i].toString(16))
            }
            builder.append("::")
            for (i in bestStart + bestLength until groups.size) {
                if (i > bestStart + bestLength) builder.append(':')
                builder.append(groups[i].toString(16))
            }
            return builder.toString()
        }
    }
}

/** A candidate CIDR that overlaps a local LAN or VPN interface block. */
data class EasyTierRouteConflict(
    val cidr: String,
    val reason: String
)

/**
 * Result of a route planning pass.
 *
 * @property localLanCidrs   directly connected LAN blocks that must stay direct
 * @property easyTierCidrs   blocks that should be routed through EasyTier
 * @property vpnRoutes       blocks that must be added to the VpnService builder
 * @property conflicts       overlaps that were resolved in favour of the local LAN
 */
data class EasyTierRoutePlan(
    val localLanCidrs: List<String> = emptyList(),
    val easyTierCidrs: List<String> = emptyList(),
    val vpnRoutes: List<String> = emptyList(),
    val conflicts: List<EasyTierRouteConflict> = emptyList()
)

/**
 * Turns the set of EasyTier mesh CIDRs into a precise routing plan.
 *
 * The old implementation routed the whole private address space
 * (`10/8`, `172.16/12`, `192.168/16`) through EasyTier.  That captured the
 * device's real LAN as well, so LAN-only services such as LocalSend and KDE
 * Connect could no longer reach each other.  This planner keeps only the
 * actual mesh/remote networks and subtracts everything reachable on the
 * local link.
 */
object EasyTierRoutePlanner {

    /** Safety cap for CIDR subtraction; a /8 minus a /24 needs at most ~20 blocks. */
    private const val MAX_ROUTES_PER_CANDIDATE = 256

    /** Peer advertisements must be at least this specific (IPv4). */
    const val MIN_PEER_IPV4_PREFIX = 16

    /** Peer advertisements must be at least this specific (IPv6). */
    const val MIN_PEER_IPV6_PREFIX = 48

    /** Conflict reason used when a peer advertises an overly broad range. */
    const val PEER_RANGE_TOO_BROAD =
        "rejected: peer advertisement is broader than /16 (IPv4) or /48 (IPv6); " +
            "add it as an extra route to trust it"

    /**
     * Build a route plan.
     *
     * @param localLanCidrs    directly connected LAN blocks (Wi-Fi/Ethernet/USB)
     * @param vpnClientCidr    v2rayNG's own VPN client block, never routed through EasyTier
     * @param meshCidrs        CIDRs discovered from EasyTier (`my_node_info`, routes, proxy_cidrs)
     * @param virtualIp        configured EasyTier virtual IP; its subnet is always a candidate
     * @param extraCidrs       user supplied remote networks that should use EasyTier
     * @param lastKnownCidrs   mesh CIDRs persisted by the previous session
     * @param bypassLocalLan   when true, local LAN blocks stay direct and are subtracted
     */
    fun plan(
        localLanCidrs: Collection<String>,
        vpnClientCidr: String?,
        meshCidrs: Collection<String>,
        virtualIp: String?,
        extraCidrs: Collection<String>,
        lastKnownCidrs: Collection<String> = emptyList(),
        bypassLocalLan: Boolean = true
    ): EasyTierRoutePlan {
        val localLan = normalizeAll(localLanCidrs)
        val vpnClient = vpnClientCidr?.let { EasyTierCidr.parse(it)?.let(::networkOf) }

        val candidates = LinkedHashSet<EasyTierCidr>()
        val conflicts = mutableListOf<EasyTierRouteConflict>()

        normalizeVirtualIp(virtualIp)?.let { virtualBlock ->
            if (isSafeMeshCidr(virtualBlock.toString())) {
                candidates.add(virtualBlock)
            } else {
                conflicts.add(
                    EasyTierRouteConflict(
                        virtualBlock.toString(),
                        "virtual IP is outside the allowed private ranges"
                    )
                )
            }
        }

        // Peer-advertised ranges arrive over the network and are untrusted, so
        // they must stay specific: otherwise one mesh member could advertise
        // 10/8 or 192.168/16 and capture every private destination the device
        // talks to.  Ranges entered by the local user ("extra routes") are an
        // explicit trust decision and keep the wider isSafeMeshCidr policy.
        (meshCidrs + lastKnownCidrs).forEach { raw ->
            val cidr = EasyTierCidr.parse(raw)?.let(::networkOf) ?: return@forEach
            when {
                !isSafeMeshCidr(cidr.toString()) -> conflicts.add(
                    EasyTierRouteConflict(cidr.toString(), "rejected: not a private mesh range")
                )
                !isAcceptablePeerCidr(cidr.toString()) -> conflicts.add(
                    EasyTierRouteConflict(cidr.toString(), PEER_RANGE_TOO_BROAD)
                )
                else -> candidates.add(cidr)
            }
        }

        extraCidrs.forEach { raw ->
            val cidr = EasyTierCidr.parse(raw)?.let(::networkOf) ?: return@forEach
            if (isSafeMeshCidr(cidr.toString())) {
                candidates.add(cidr)
            } else {
                conflicts.add(
                    EasyTierRouteConflict(cidr.toString(), "rejected: not a private mesh range")
                )
            }
        }

        val collapsed = collapse(candidates)
        val reserved = ArrayList<EasyTierCidr>(localLan.size + 1)
        if (bypassLocalLan) reserved.addAll(localLan)
        vpnClient?.let { reserved.add(it) }

        val easyTier = mutableListOf<EasyTierCidr>()
        for (candidate in collapsed) {
            val overlap = reserved.firstOrNull { it.intersects(candidate) }
            if (overlap != null) {
                conflicts.add(
                    EasyTierRouteConflict(
                        candidate.toString(),
                        "overlaps ${overlap}${if (bypassLocalLan) "; local LAN wins" else ""}"
                    )
                )
            }
            val remaining = subtractAll(candidate, reserved)
            if (remaining.size > MAX_ROUTES_PER_CANDIDATE) {
                conflicts.add(
                    EasyTierRouteConflict(
                        candidate.toString(),
                        "too many resulting routes; local LAN wins"
                    )
                )
                continue
            }
            easyTier.addAll(remaining)
        }

        val easyTierCidrs = collapse(easyTier).map { it.toString() }.sorted()
        val directCidrs = if (bypassLocalLan) localLan.map { it.toString() }.sorted() else emptyList()

        return EasyTierRoutePlan(
            localLanCidrs = directCidrs,
            easyTierCidrs = easyTierCidrs,
            vpnRoutes = easyTierCidrs,
            conflicts = conflicts.distinct()
        )
    }

    /**
     * Parse a comma/whitespace separated list of CIDRs.
     */
    fun parseList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',', '\n', '\r', ' ', '\t', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { EasyTierCidr.parse(it)?.toString() }
            .distinct()
    }

    /**
     * Validates a peer-advertised or user-supplied mesh CIDR.
     *
     * Allowed ranges:
     * - IPv4 private: 10/8, 172.16/12, 192.168/16
     * - IPv4 link-local: 169.254/16
     * - IPv4 CGNAT: 100.64/10
     * - IPv6 ULA: fc00::/7
     * - IPv6 link-local: fe80::/10
     */
    fun isSafeMeshCidr(cidr: String): Boolean {
        val parsed = EasyTierCidr.parse(cidr) ?: return false
        if (parsed.prefixLength == 0) return false
        if (!parsed.isIpv6 && parsed.prefixLength <= 7) return false

        return if (!parsed.isIpv6) {
            val first = parsed.address[0].toInt() and 0xFF
            val second = parsed.address[1].toInt() and 0xFF
            when {
                first == 10 -> true
                first == 172 && second in 16..31 -> true
                first == 192 && second == 168 -> true
                first == 169 && second == 254 -> true
                first == 100 && second in 64..127 -> true
                else -> false
            }
        } else {
            val first = parsed.address[0].toInt() and 0xFF
            val second = parsed.address[1].toInt() and 0xFF
            when {
                first == 0xFC || first == 0xFD -> true
                first == 0xFE && second in 0x80..0xBF -> true
                else -> false
            }
        }
    }

    /**
     * Whether a range advertised by a mesh peer is specific enough to be
     * trusted for routing.
     *
     * Peer advertisements are network input, not local configuration.  A
     * member that advertises `10.0.0.0/8` or `192.168.0.0/16` would otherwise
     * redirect every private destination of this device into the mesh.  Only
     * ranges of /16 (IPv4) or /48 (IPv6) and narrower are accepted; broader
     * ranges must be added explicitly as extra routes.
     */
    fun isAcceptablePeerCidr(cidr: String): Boolean {
        val parsed = EasyTierCidr.parse(cidr) ?: return false
        if (!isSafeMeshCidr(parsed.toString())) return false
        val minimumPrefix = if (parsed.isIpv6) MIN_PEER_IPV6_PREFIX else MIN_PEER_IPV4_PREFIX
        return parsed.prefixLength >= minimumPrefix
    }

    /**
     * Normalize a configured virtual IP to the subnet EasyTier will use.
     *
     * EasyTier forces a bare address (or an explicit /32) to /24.  We mirror
     * that behaviour so the VPN route always matches the mesh subnet.
     */
    fun normalizeVirtualIp(value: String?): EasyTierCidr? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(':')) return null

        val withPrefix = if (trimmed.contains('/')) trimmed else "$trimmed/24"
        val parsed = EasyTierCidr.parse(withPrefix) ?: return null
        val effectivePrefix = if (parsed.prefixLength == 32) 24 else parsed.prefixLength
        return networkOf(EasyTierCidr(parsed.address, effectivePrefix))
    }

    private fun normalizeAll(values: Collection<String>): List<EasyTierCidr> =
        values.mapNotNull { EasyTierCidr.parse(it)?.let(::networkOf) }
            .distinct()
            .sortedWith(cidrComparator)

    private fun networkOf(cidr: EasyTierCidr): EasyTierCidr =
        EasyTierCidr(cidr.address, cidr.prefixLength)

    /**
     * Remove blocks already covered by a less specific block.
     */
    private fun collapse(cidrs: Collection<EasyTierCidr>): List<EasyTierCidr> {
        val sorted = cidrs.distinct().sortedWith(cidrComparator)
        val result = ArrayList<EasyTierCidr>(sorted.size)
        for (cidr in sorted) {
            if (result.none { it.contains(cidr) }) result.add(cidr)
        }
        return result
    }

    private fun subtractAll(
        candidate: EasyTierCidr,
        exclusions: List<EasyTierCidr>
    ): List<EasyTierCidr> {
        val result = mutableListOf<EasyTierCidr>()
        subtract(candidate, exclusions, result)
        return result
    }

    private fun subtract(
        cidr: EasyTierCidr,
        exclusions: List<EasyTierCidr>,
        result: MutableList<EasyTierCidr>
    ) {
        if (result.size > MAX_ROUTES_PER_CANDIDATE) return

        val overlap = exclusions.firstOrNull { it.intersects(cidr) }
        if (overlap == null) {
            result.add(cidr)
            return
        }
        if (overlap.contains(cidr)) {
            return
        }
        if (cidr.prefixLength >= overlap.prefixLength) {
            // Same prefix or a more specific candidate: keep it.  The caller
            // only calls this when the exclusion is contained by the candidate
            // (or vice versa), so this is a defensive branch.
            result.add(cidr)
            return
        }

        val (first, second) = cidr.split()
        subtract(first, exclusions, result)
        subtract(second, exclusions, result)
    }

    private val cidrComparator = Comparator<EasyTierCidr> { a, b ->
        val prefix = a.prefixLength.compareTo(b.prefixLength)
        if (prefix != 0) {
            prefix
        } else {
            compareBytes(a.address, b.address)
        }
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val size = minOf(a.size, b.size)
        for (i in 0 until size) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size.compareTo(b.size)
    }
}