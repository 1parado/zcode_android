# ZCode Android

ZCode 的原生 Android 客户端（Kotlin + Jetpack Compose），目标是局域网直连桌面端 ZCode，提供会话列表与流式对话体验，并集成 UU 远程能力。

> ⚠️ 该仓库处于 Phase 1 早期开发阶段，接口与协议实现仍在演进。

## 功能概览

- **RPC 协议栈**（`core/protocol`）：对齐 ZCode `packages/rpc` 的二进制协议 —— 13 字节帧头（type/id/ack/length, u32 BE）、VQL 变长整数、两段式序列化、帧重组（含 CRC32 校验与分片重装）。
- **V4 会话协议**（`core/protocol/v4`）：`helloConversationV4` / `initializeConversationV4` 握手、conversation 与 sessions-index 双 topic 订阅、snapshot + deltas 状态归约。
- **UU 远程**（`app` / `feature/uuremote`）：URL 校验与扫码接入。

## 模块结构

```
app                    # 壳工程：导航、主题、入口
core/common            # 日志、JSON、ClientIdProvider 等基础工具
core/protocol          # RPC 二进制协议 + V4 会话协议（纯 JVM，可单测）
core/network           # 连接状态机：发现/认证/WS/握手/重连 + workspace 单一 owner
core/design            # 设计系统：ZcodeTheme、颜色与形状令牌
feature/connect        # 连接/配对页（含 DataStore 设置与加密 token 存储、扫码）
feature/sessions       # 会话列表页（sessions-index 订阅与归约）
feature/chat           # 对话页（流式收发、命令信封）
feature/uuremote       # UU 远程集成（绑定/远控深链）
```

## 构建

要求：JDK 21、Android Studio（AGP 9.2.1）。

```bash
./gradlew :core:protocol:test   # 协议层单元测试
./gradlew :app:assembleDebug    # 构建 debug APK
```

Windows 下若用户目录含中文导致 Gradle 异常，可设置：

```powershell
$env:GRADLE_USER_HOME = 'D:\gradle-home'
```

## 协议实现说明

RPC 物理帧：`[type u8][id u32BE][ack u32BE][length u32BE][body]`，body 由两段序列化拼接（header JSON 数组 + 数据段）。序列化数据类型标签与语义对齐 TS 侧 `serialization.ts`（undefined 仅 1 字节 tag；32 位整数走 VQL；其他走 JSON 文本）。帧长上限 1 MiB，超出视为协议错误。

## License

仅供 ZCode 生态使用。
