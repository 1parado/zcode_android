# ZCode Android 测试方案（Test Plan）

> 适用对象：`packages/android`（com.zai.zcode，Kotlin + Jetpack Compose）
> 当前版本基线：v0.2.0 (versionCode 2) / git `d866abd`
> 文档维护：测试用例变更与 bug 修复后同步更新本目录下所有文档。

## 1. 测试范围

| 类别 | 内容 | 说明 |
|---|---|---|
| 单元测试回归 | `core/protocol`、`core/markdown`、`core/network`、`feature/sessions` | 纯 JVM，`./gradlew test`，CI 门禁 |
| 功能测试 | 连接配对、会话列表、流式对话、终端面板、UU 远程、WebView 远控壳 | 真机手工 + adb 辅助 |
| 兼容性测试 | Android 版本、厂商 ROM（重点华为 EMUI 后台策略）、权限拒绝、分辨率 | 见兼容性矩阵 |
| 回归测试 | 每次合入后：单测全量 + 主链路冒烟 + 已修复 bug 定向回归 | 见 BUG_TRACKING.md 回归清单 |
| 性能测试 | 启动速度、内存占用、耗电量、流量 | 见 PERFORMANCE.md |

不在本期范围：自动化 UI 测试（Espresso/Compose UI Test，Phase 2 引入）、弱网专项（可用 `adb shell cmd netpolicy` 辅助）。

## 2. 测试环境

| 项目 | 值 |
|---|---|
| 构建要求 | JDK 21、AGP 9.2.1、Gradle 9.4.1（distributionSha256Sum 已钉） |
| **本机跑测坑（重要）** | ① Windows 用户目录含中文 → Gradle Test Worker 报 `ClassNotFoundException: GradleWorkerMain`，必须用 ASCII 路径的 `GRADLE_USER_HOME`（本机已建立 `E:\gradle-home` 缓存）；② 项目钉了 daemon JVM 21.0.7（gradle-daemon-jvm.properties），github 下载被墙时加 `JAVA_HOME` 指向本机 JDK + `-Dorg.gradle.java.installations.auto-download=false`。本机可用命令模板见下方 |
| 本机单测命令 | `cd packages/android && GRADLE_USER_HOME=E:/gradle-home JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot" ./gradlew :core:protocol:test :core:markdown:test :feature:sessions:test --console=plain -Dorg.gradle.java.installations.auto-download=false` |
| 构建 APK | `./gradlew :app:assembleDebug`；发布包由 GitHub Actions（`.github/workflows/release.yml`，tag 触发） |
| 真机（已接入） | 华为 JER-AN10 / Android 12 (API 31) / EMUI 14.2.0 / arm64-v8a / 1080x2340 @ 480dpi |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| 测试桌面端 | ZCode 桌面版（提供 RPC host / 远控二维码 / UU 远程宿主）；**未连接桌面端时 CONN-01/02/03、SESS-\*、CHAT-\*、TERM-\*、UU-\*、BG-01 均为 Blocked** |

## 3. 兼容性矩阵

| 维度 | 覆盖项 | 优先级 |
|---|---|---|
| OS 版本 | Android 8.0 (minSdk 26) / 10 / 12（有真机）/ 13 / 14 / 15 / 16 (targetSdk 36) | P0: 12 实测；P1: 8.0 与 15 模拟器 |
| 厂商 ROM | 华为 EMUI（后台查杀激进，前台服务验证 P0）、小米 MIUI（自启动授权）、原生 Pixel | P1 |
| 权限拒绝 | 相机拒绝（扫码）、通知拒绝（前台服务通知，API 33+） | P0 |
| 屏幕形态 | 1080x2340（真机）、720p 小屏、折叠屏展开态、横屏 | P2 |
| 网络 | WiFi / 蜂窝切换、飞行模式、VPN 叠加 | P1 |
| CPU ABI | arm64-v8a（release 仅此 ABI 时） | P1 |

## 4. 进入 / 退出标准

**进入**：CI 单测绿 + `:app:assembleDebug` 成功产出 APK + 冒烟主链路（连接→会话→对话）可走通。

**退出**：P0/P1 缺陷全部修复并回归通过；P2/P3 有记录且有处理结论；性能基线指标（见 PERFORMANCE.md）不劣于上一版本 10% 以上（劣化需归因）。

## 5. 缺陷管理

- 缺陷提交模板、当前缺陷清单、回归验证记录：见 [BUG_TRACKING.md](BUG_TRACKING.md)。
- 缺陷等级：P0（阻断/崩溃/数据错误/编译失败）、P1（主功能不可用但可绕行/高频体验缺陷）、P2（次要功能/健壮性）、P3（细节/文案/风格）。
