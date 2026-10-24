package com.easytier.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Codec for the cross-process "last known mesh routes" file.
 *
 * Current format:
 * ```
 * { "updatedAt": <epoch millis>, "cidrs": ["192.168.50.0/24", ...] }
 * ```
 *
 * Files written before `updatedAt` existed contain only `cidrs`.  Their age is
 * taken from the file modification time, so upgrading neither trusts an old
 * baseline forever nor silently drops a fresh one.
 *
 * A stale baseline is dangerous: it keeps routing traffic for a peer that has
 * left the mesh, which black-holes that subnet or hands it to a different
 * peer.  Every value is re-validated on read, so a corrupt or edited file
 * cannot inject a public range.
 *
 * The codec has no Android dependency and is covered by JVM tests.
 */
internal object LastKnownRoutes {

    const val KEY_UPDATED_AT = "updatedAt"
    const val KEY_CIDRS = "cidrs"

    /** Serialize [cidrs], keeping only safe ranges, sorted and unique. */
    fun encode(cidrs: Collection<String>, updatedAt: Long): String {
        val safe = cidrs
            .filter { EasyTierRoutePlanner.isSafeMeshCidr(it) }
            .distinct()
            .sorted()
        return JsonObject().apply {
            addProperty(KEY_UPDATED_AT, updatedAt)
            add(KEY_CIDRS, JsonArray().apply { safe.forEach { add(it) } })
        }.toString()
    }

    /**
     * @param raw file content, or null when the file does not exist
     * @param fileModifiedAt modification time used for legacy files without
     *   an `updatedAt` field
     * @param ttlMs entries older than this are treated as absent
     */
    fun decode(raw: String?, fileModifiedAt: Long, now: Long, ttlMs: Long): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val json = try {
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Throwable) {
            null
        } ?: return emptyList()

        val updatedAt = json.get(KEY_UPDATED_AT)
            ?.takeIf { it.isJsonPrimitive }
            ?.asLong
            ?: fileModifiedAt
        if (now - updatedAt > ttlMs) return emptyList()

        val array = json.getAsJsonArray(KEY_CIDRS) ?: return emptyList()
        return array
            .mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
            .filter { EasyTierRoutePlanner.isSafeMeshCidr(it) }
            .distinct()
            .sorted()
    }
}