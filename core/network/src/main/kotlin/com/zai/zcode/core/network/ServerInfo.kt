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
    /**
     * @param workspaceIdentity 远程链路的身份（AGENTS.md「Workspace Identity」）。
     * 修复依据（真机 v0.2.14）：identity 原先只活在 ZcodeConnection 的旁路 map 里，
     * 任何没走该 map 的路径（LAN 直连、deeplink 重建、进程恢复后的反序列化）都会
     * 静默丢 identity，进而用裸 path 去撞桌面按 identity 的精确匹配。把它放进
     * workspace 模型随对象传递（对齐 zemote main_shell.dart:16-29 全程携带）。
     */
    data class Workspace(
        val path: String,
        val label: String?,
        val workspaceIdentity: String? = null,
        // 修复依据（用户报「/ 面板无响应 + 上传 The path argument undefined」）：relay
        // bootstrap 的 workspace target 带 remoteSessionId（web-remote-control.ts:27-28，
        // 官方 useSkills 经 useWorkspaceServicesResolution 全程携带）——缺它 host 无法把
        // catalog/attachment 路由到远端 attachment 的 CLI，转而 spawn 新进程并在
        // prepareRuntimeState 崩溃（host 日志 path.join(undefined)）。
        val remoteSessionId: String? = null,
    ) {
        /** 服务端 key 归一：`workspaceIdentity?.trim() || workspacePath`。 */
        val key: String get() = com.zai.zcode.core.protocol.v4.workspaceKeyOf(path, workspaceIdentity)
    }

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
                        workspaceIdentity = (w["workspaceIdentity"] as? JsonPrimitive)
                            ?.takeIf { p -> p !is JsonNull && p.isString }?.content,
                        remoteSessionId = (w["remoteSessionId"] as? JsonPrimitive)
                            ?.takeIf { p -> p !is JsonNull && p.isString }?.content,
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
