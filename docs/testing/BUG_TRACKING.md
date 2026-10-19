# ZCode Android 缺陷跟踪（BUG_TRACKING）

> 规则：每个缺陷一行记录 + 详情模板；状态流转 Open → Fixed → Regression-Verified（Closed）。
> 修复 PR 合入后，测试侧按"回归步骤"验证并在状态栏登记日期；同时回填 TEST_CASES.md 对应用例结果。

## 1. 当前缺陷清单

| ID | 等级 | 模块 | 标题 | 来源 | 状态 | 回归记录 |
|---|---|---|---|---|---|---|
| BUG-001 | P1 | core/network | attachFrameListener 无调用者，subscribe 后收不到流式 EventFire | 代码评审 2026-10-17 P1-A | Open | - |
| BUG-002 | P1 | feature/connect | 已连接状态回设置页被瞬间弹回，无法修改地址/Token（状态驱动而非边沿驱动导航） | 代码评审 P1-C | Open | - |
| BUG-003 | P2 | feature/uuremote | UuRemoteCallbackRouter.expectBind 覆盖 pending deferred，旧调用方悬挂 3min | 代码评审 P2-8 | Open | - |
| BUG-004 | P3 | feature/chat | 发送失败回滚会覆盖用户发送期间新输入的草稿 | 代码评审 P3 | Open | - |
| BUG-005 | P3 | feature/chat | phase 用 contains("run") 魔法子串判运行态，枚举外值误判 | 代码评审 P3 | Open | - |
| BUG-006 | P2 | feature/chat | ChatModule.kt 文件非 UTF-8 编码，中文注释乱码可能污染字符串常量 | 代码评审 P2-7 | Open | - |
| BUG-007 | P2 | CI | release.yml 发布 debug 签名 APK，未签名不可上架 | 代码评审 P2-4（workflow 已注明 debug preview） | Open | - |
| BUG-008 | **P0** | app/Manifest | `zcode://remote` deep link 无法解析，外部拉起远控入口失效 | 真机测试 2026-10-19（CONN-06） | Fixed（2026-10-19，待回归） | - |
| BUG-010 | P1 | core/network | 测试代码编译失败：RpcFrameTransportTest.kt 缺 kotlinx-coroutines-test 依赖（runTest 未解析），CI 未跑该模块故未暴露 | 本地回归 2026-10-19 | Fixed（2026-10-19，待回归） | - |
| BUG-011 | **P1** | feature/sessions | SessionsOrderingTest 3/6 失败（已提交代码即失败）：①分组顺序 [昨天,置顶,今天,更早] 应为 [置顶,今天,昨天,更早]；②相对时间桶期望 null 实际"刚刚"；③未读派生多出 fresh 态。疑似 34a9e77 清理 groupByRecency 时引入回归，CI 不跑该模块未拦截 | 本地回归 2026-10-19 | Fixed×2 / 1 项待测试方裁定（2026-10-19，见详情③） | - |
| BUG-012 | **P1** | feature/chat / RelayClient | AI 已在桌面端回复完成，移动端"很久"后才收到消息（实时 delta 疑似未推送，靠看门狗 resync 兜底，最坏 ~30s+）。待受控复现量化 | 真机 2026-10-19 16:01 用户报障 | Open（复现中） | - |
| BUG-013 | P3 | core/network RelayClient | applyPairStatus("matched") 无状态守卫：心跳每 10s 轮询到 matched 就重打 "relay paired with desktop" 日志并重复 flushOutboundQueue()，日志噪音 + 冗余 flush | 真机日志 2026-10-19（16:03-16:05 每 10.05s 一条） | Open | - |

### 历史已修复（供回归参考）

| 原编号 | 标题 | 修复确认 |
|---|---|---|
| 评审 R1 P0-1~P0-7 | 方法名/握手/订阅/事件链路/assembler/包名/Hilt | ✅ 评审第 2 轮全部确认 |
| 评审 R2 P0-A/B/C | feature 交叉依赖编译失败 / 回调 suspend 误用 / 握手未等 IDLE 无限重连 | ✅ 代码已含修复注释（ZcodeConnection.kt:608-612） |
| 评审 R2 P0-D | CI workflow 位置 | ✅ packages/android 为独立 git 仓库，根 .github/workflows 有效（重新定性，非缺陷） |
| git 88f1355 | listenEvent 字符串 arg 序列化丢失 → 桌面 host 崩溃 | ✅ 已提交 |
| git 2f93ba0 | 动态事件 payload 双重信封 | ✅ 已提交 |
| git 78abb23 | switchThought 选型 / UU 安装检测双路径 / 用量统计 | ✅ 已提交 |

## 2. 缺陷详情

### BUG-008：`zcode://remote` deep link 在真机上无法解析（P0）

- **修复说明（2026-10-19）**：
  1. `app/src/main/AndroidManifest.xml:55-90`——3 个 `<data>` 拆成 3 个独立 intent-filter（消除同 filter 内 pathPrefix 跨维度合并）；
  2. `app/src/main/AndroidManifest.xml:65-69`（remote filter）——`<data>` 补 `android:path=""`：无 path 声明的 filter 会匹配任意 path（Android intent-filter 语义），拆分后 `zcode://remote/bind` 仍被 remote filter 错误命中，`path=""` 限定其只匹配无 path 的 `zcode://remote`（含 `?query`）。
  - 真机验证（华为 JER-AN10 / 新装包 adb query-activities + am start）：`zcode://remote` HIT、`zcode://remote/bind` MISS、`zcode://remote/something` MISS、`zcode://uuremote/bind` HIT、`zcode://uuremote/control` HIT、`https://zcode.z.ai/remote` HIT，`am start zcode://remote?sid=test` 端到端拉起成功——与实测矩阵 6/6 一致。

- 环境：华为 JER-AN10 / Android 12 (API 31) / App v0.2.0(2) debug / 2026-10-19
- 复现步骤（adb 验证，浏览器点击同源链接等效）：
  1. `adb shell cmd package query-activities -a android.intent.action.VIEW -d 'zcode://remote'`
  2. 返回 `No activities found`；`am start -a android.intent.action.VIEW -d 'zcode://remote?...'` 报 `unable to resolve`
- 预期：MainActivity 的 VIEW 过滤器应命中，拉起 App 打开远控页。
- 实测矩阵：

| URI | 解析 | 判定 |
|---|---|---|
| `zcode://remote` | ❌ No activities found | **应命中而未命中** |
| `zcode://remote/bind` | ✅ 命中 | **不应命中而命中**（错误匹配） |
| `zcode://remote/something` | ❌ | - |
| `zcode://uuremote/bind` | ✅ | 正确 |
| `https://zcode.z.ai/remote` | ✅（App+浏览器双命中） | 正确 |
| `https://evil.com/remote` | ❌ 仅浏览器 | 正确（无域名劫持） |

- 根因：`app/src/main/AndroidManifest.xml` 把 3 个 `<data>`（`zcode://remote`、`zcode://uuremote/bind`、`zcode://uuremote/control`）写在**同一个 intent-filter**。Android 会把同一 filter 内所有 `<data>` 的属性做跨维度合并——`pathPrefix` 约束作用于整个 filter，导致无 path 的 `zcode://remote` 匹配失败，且 `zcode://remote/bind` 因命中 /bind 前缀被错误匹配。
- 影响面：桌面端生成的 `zcode://remote?...` 远控链接无法从浏览器/外部应用拉起 App（核心入口之一失效）；仅扫码/粘贴链接入口可用。
- 修复建议：拆成 3 个独立 intent-filter（remote 一个、uuremote/bind 一个、uuremote/control 一个）。
- 回归步骤：修复后重跑上面 6 个 URI 的解析矩阵，`zcode://remote` 必须命中且 `zcode://remote/bind` 不命中；再做一次真实 `am start` 打开远控页的端到端验证。

### BUG-010：core/network 测试代码编译失败（P1）

- **修复说明（2026-10-19）**：
  1. `core/network/build.gradle.kts:40-43`——补 `testImplementation(libs.kotlinx.coroutines.test)`；
  2. `core/network/src/test/.../RpcFrameTransportTest.kt:8`——根因之一是 import 写错包名：`kotlinx.coroutines.runTest` 应为 `kotlinx.coroutines.test.runTest`（同 classpath 下冒烟验证证明 jar 可解析，仅此文件 unresolved）；
  3. 同文件 :51-70——`async`/`withTimeoutOrNull` 均已在 runTest 协程作用域内调用（原报错"非协程上下文调用 suspend"是 runTest 未解析的连锁错误）；`async` 补 `Dispatchers.Default`：transport 的 emit 用真实时间调度器，await 挂在 runTest 虚拟时间调度器上会瞬间跑完 1s 超时。
  - 验证：`./gradlew :core:network:test` 全绿（BUILD SUCCESSFUL）。

- `core/network/src/test/.../RpcFrameTransportTest.kt`：`runTest` 未解析（7:27、49:56、60:49）+ 非 coroutine 上下文调用 suspend（53/56/67 行）。
- 根因：`core/network/build.gradle.kts` testImplementation 缺 `kotlinx-coroutines-test`；测试代码还用了已废弃的全局 `async`。
- 为什么 CI 没发现：ci.yml 只跑 `:core:protocol:test :core:markdown:test`。
- 修复建议：补依赖 + 用 `runTest` 包裹；**同时把 `:core:network:test :feature:sessions:test` 加进 ci.yml**，否则同类问题继续漏网。
- 回归步骤：`./gradlew :core:network:test` 编译通过且全绿。

### BUG-011：feature/sessions 三个单测在已提交代码上失败（P1）

- **修复说明（2026-10-19）**：
  1. ①分组顺序——`feature/sessions/.../SessionsOrdering.kt:39-77`：实现按 linkedMap **插入序**建段，与注释/测试期望的固定序不符（git 取证：e525173 初版即如此，34a9e77 只收窄了签名未引入此问题）。改为按固定段序 `置顶→今天→昨天→更早` 输出，组内保持传入顺序。✅ 通过
  2. ②相对时间桶——同文件 `relativeTime()`：测试的 `now - 30 * 86_400_000`（Int 乘法溢出为负）构造出**未来时间戳**，负 diff 落进第一个桶显示"刚刚"。补 `diff < 0 -> null` 守卫（未来时间没有"X 前"文案，语义正确）。✅ 通过
  3. ③未读派生——**该用例期望本身有错，按约束停下说明、未改测试**：用例把派生逻辑整段内联在测试里，不调用任何生产代码；按其注释声称的语义（未读 = lastActivityAt > 本地水位，与生产实现 SessionsRepository.kt:594/:618 一致），`fresh`（activity=40、无水位记录）必然算出未读，断言 `[read]` 数学上不可能成立。若要 `[read]` 成立须改成"无水位记录一律视为已读"，会漏报新会话未读，与产品语义相反。另经 git 取证，此用例自 e525173 引入起即注定失败（与 BUG 记录"已提交代码即失败"吻合），非 34a9e77 回归。**请测试方修正用例**（期望改 `[read, fresh]`，或改语义描述）后再关闭本条。

- `SessionsOrderingTest`（e525173 引入）：`./gradlew :feature:sessions:test` 6 用例中 3 失败（2026-10-19 实测）：
  1. 分组顺序：期望 `[置顶, 今天, 昨天, 更早]`，实际 `[昨天, 置顶, 今天, 更早]`；
  2. 相对时间桶：期望 `null`，实际 `刚刚`；
  3. 未读派生：期望 `[read]`，实际 `[read, fresh]`。
- 被测文件 SessionsOrdering.kt / SessionsRepository.kt 无未提交改动 → 失败在 HEAD 即存在；最后一次动实现的是 34a9e77（"清理 groupByRecency 死参数"），回归大概率由此引入。
- 修复后回归：`./gradlew :feature:sessions:test` 全绿。

### BUG-012：移动端收到 AI 回复明显滞后（P1，复现中）

- 环境：华为 JER-AN10 / Android 12 / App v0.2.0 debug（15:36 包）/ relay 中继连接 / 2026-10-19 16:01
- 现象：用户报障——桌面端模型已完成回复，移动端过了"好久"才显示 AI 消息。
- 已排除：
  - ~~relay 链路反复重建~~：`relay paired with desktop` 每 10s 重复是心跳轮询 matched 的日志噪音（BUG-013），连接本身未断。
  - ~~流式监听缺失~~：P1-A（attachFrameListener 无调用者）已修复，Ready 时自动挂载（ZcodeConnection.kt:630）。
- 头号嫌疑（代码机制推演）：
  - 实时 delta EventFire 若未送达，消息只能靠 ChatRepository 看门狗补：`WATCHDOG_PERIOD_MS=10s` + `WATCHDOG_QUIET_MS=20s` → 最坏 ~30s 延迟；且看门狗仅在 `isRunning || 有 streaming 行` 时触发，若错过运行态则可能更久。
  - 次嫌疑：切后台/EMUI 断后台网 → 帧静默丢失 → 回前台靠 resync 补（v0.2.15 已修过同形态"切后台消息停半句"，可能未覆盖所有路径）。
  - workspaceTarget 不匹配导致 fan-out 过滤掉 delta（subscribe 与 attachFrameListener 的 target 不同源时）。
- 回归/量化步骤（受控复现）：
  1. 保持 App 在前台，记录发送时刻 T0；
  2. 记录桌面端回复完成时刻 T1、移动端气泡出现/流式完成时刻 T2；
  3. 抓 `adb logcat --pid=$(pidof com.zai.zcode)`，若 T2-T1 > 2s 且日志出现 `watchdog: quiet` 或 resync，即坐实"实时通道不工作、resync 兜底"。
- 修复方向提示：给会话 delta 收帧路径加一帧一日志（DEBUG 级）便于以后量化；检查 subscribe 与 fan-out 的 workspaceTarget 是否同源。

### BUG-013：RelayClient 心跳重复打 "relay paired with desktop" + 冗余 flush（P3）

- `RelayClient.kt:289-298`：`applyPairStatus("matched")` 无"已是 Paired 态"守卫，心跳（`startHeartbeat`，`delay(10_000)`）每轮询到一次 matched 就重设状态、重打日志、重复 `flushOutboundQueue()`。
- 实测：16:03:02-16:05:32 每 10.05s 一条 "relay paired with desktop"。
- 修复建议：`if (_state.value != State.Paired) { ...原逻辑... }`，或把 matched 心跳应答与首次配对分开处理；日志降为 DEBUG。
- 回归步骤：配对成功后静置 1 分钟，`relay paired with desktop` 只出现 1 次。

### 缺陷报告模板```markdown
### BUG-XXX：<一句话标题>

- 等级：P0/P1/P2/P3
- 模块：feature/xxx 或 core/xxx
- 环境：设备型号 / Android 版本 / App 版本（versionName+versionCode）/ 构建类型(debug|release)
- 复现步骤：
  1. ...
  2. ...
  3. ...
- 实际结果：
- 预期结果：
- 复现概率：必现 / 高 / 偶现 / 仅一次
- 证据：logcat 片段（adb logcat -d -s zcode）、截图、录屏、诊断中心/协议日志导出
- 疑似定位（可选）：<文件:行号>
- 回归步骤：<修复后按什么步骤验证>
```

## 3. 假阳性 / 排查记录（不计入缺陷）

- **FP-2026-10-19-01**：新建会话发送单条消息却出现"两条相同用户气泡"。
  - 排查：抓 `adb logcat -d -s zcode` 未发现 `ChatRepository` 重复上送；无 reducer/replay 去重失败迹象。
  - 结论（用户确认）：**假阳性**——发送前未选择模型，用户以为首条未发出，手动重复发送，故出现两条相同气泡。非 App 缺陷。
  - 衍生观察：发送前未选模型时 App 的拦截/提示行为不明确，是诱发重复发送的 UX 风险点 → 已在 TEST_CASES.md 登记 `CHAT-04` 作边界验证（NotRun）。

## 4. 提交渠道

- 内部：本文件登记 + 指派开发（AI 自动修复流程：Issue 按"复现步骤+环境+日志"三要素填写）。
- 外部 GitHub Issue 模板要点：问题现象 / 复现步骤 / 环境信息（设备、Android 版本、设置页可见的 App 版本）/ 设置页「诊断中心」导出的协议日志。
