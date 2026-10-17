package com.zai.zcode.core.common

import kotlinx.serialization.json.Json

/**
 * 全局 JSON 配置。协议要求 ignoreUnknownKeys：协议演进（加字段）不能让旧客户端崩，
 * 这与 TS 端 zod passthrough/optional 的宽容语义对齐。
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    isLenient = false
    classDiscriminator = "kind"
}
