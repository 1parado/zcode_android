package com.zai.zcode.core.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray

/**
 * GET /api/server-info 响应的宽容承载（忽略未知键，容忍字段缺失）。
 * 发现端点是唯一 REST 调用；其余全部走 WS RPC。
 */
data class ServerInfo(
    val serverId: String?,
    val version: String?,
    val protocolVersion: Int?,
    val authRequired: Boolean,
    val workspaces: List<Workspace>,
) {
    data class Workspace(val path: String, val label: String?)

    companion object {
        fun fromJson(obj: JsonObject): ServerInfo {
            val protocol = (obj["protocolVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
            val authRequired = (obj["authRequired"] as? JsonPrimitive)?.let {
                it !is JsonNull && it.content == "true"
            } == true
            val workspaces = (obj["workspaces"] as? JsonArray ?: JsonArray(emptyList()))
                .mapNotNull { it as? JsonObject }
                .mapNotNull { w ->
                    val path = (w["path"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && p.isString }?.content
                        ?: return@mapNotNull null
                    Workspace(
                        path = path,
                        label = (w["label"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && p.isString }?.content,
                    )
                }
            fun str(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
            return ServerInfo(
                serverId = str("serverId"),
                version = str("version"),
                protocolVersion = protocol,
                authRequired = authRequired,
                workspaces = workspaces,
            )
        }
    }
}
