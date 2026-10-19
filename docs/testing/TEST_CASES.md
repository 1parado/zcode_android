# ZCode Android 测试用例库

> 维护约定：新增/修改用例需更新版本号与"最近更新"日期；执行后在执行记录表中登记结果。
> 结果标记：Pass / Fail / Blocked / Skip / NotRun。Fail 项在 BUG_TRACKING.md 建缺陷并双向引用用例 ID。

## 1. 连接与配对（feature/connect）

| ID | 用例 | 前置 | 步骤 | 预期 | 优先级 | 结果 |
|---|---|---|---|---|---|---|
| CONN-01 | 手动输入连接 | 桌面端已启动并开启远控/远程端口 | 输入正确 host + token，点连接 | 状态 Handshaking→Ready，进入会话列表 | P0 | |
| CONN-02 | 错误 token 连接 | 同上 | 输入错误 token | 认证失败有明确提示，不崩溃，可重试 | P0 | |
| CONN-03 | host 不可达 | 桌面端未启动 | 输入错误 host 连接 | 连接超时/失败提示，重试不叠加重连风暴（观察 logcat 重连间隔有退避） | P0 | |
| CONN-04 | 扫码配对 | 桌面端展示配对二维码 | 扫码接入 | 识别后经 RemoteControlUrlPolicy 校验通过并连接 | P0 | |
| CONN-05 | 相机权限拒绝 | 首次使用扫码 | 拒绝相机权限 | 给出引导文案，App 不崩溃；再进时仍可从设置开启 | P0 | **Pass**（2026-10-19 真机：系统弹窗正常；拒绝后底部显示红色引导"相机权限被拒绝，请在系统设置中允许 ZCode 使用相机后重试"；无崩溃） |
| CONN-06 | deep link 打开远控 | App 已安装 | adb shell am start -a android.intent.action.VIEW -d "zcode://remote?token=..." | 拉起 App 打开远控 WebView，query 参数透传 | P0 | **Fail → BUG-008**（zcode://remote 不解析，实测 2026-10-19） |
| CONN-07 | https 链接打开远控 | - | 点击 https://zcode.z.ai/remote?... 链接 | 经 App 分发打开远控页 | P1 | Pass（解析器双命中，实测 2026-10-19） |
| CONN-08 | 非法 URL 拒绝（生产包） | release 包 | 构造 http://evil.example/remote、https://zcode.z.ai.evil.com/remote、https://zcode.z.ai/other | resolve 返回 null，不进入 WebView（单测覆盖 RemoteControlUrlPolicy） | P0 | 部分验证：evil.com 域不被 App 劫持 ✅（解析器层）；resolve 单测待跑 |
| CONN-09 | debug 包局域网白名单 | debug 包 | http://192.168.x.x:5173/remote、http://localhost:5173/remote 可开；http://10.x 可开；http://8.8.8.8/remote 拒绝 | 白名单行为与 isDebugHost 一致 | P1 | |
| CONN-10 | 最近链接列表 | 曾成功打开过远控链接 ≥6 条 | 打开首页 | 仅保留最近 5 条；展示设备名/host/时间；不展示完整链接（含 token） | P1 | |
| CONN-11 | 已连接后回设置页 | 连接 Ready | 从会话页进入设置/连接页 | 页面可正常编辑地址与 token，不被"瞬间弹回"（评审 P1-C 回归点） | P0 | |
| CONN-12 | token 加密存储 | 已保存 token | 检查 data/data 数据 / 卸载重装 | token 经 SecureTokenStore 加密，明文不出现在 prefs；重装后需重新输入 | P1 | |

## 2. 会话与对话（feature/sessions + feature/chat）

| ID | 用例 | 前置 | 步骤 | 预期 | 优先级 | 结果 |
|---|---|---|---|---|---|---|
| SESS-01 | 会话列表加载 | 连接 Ready | 进入会话页 | sessions-index 订阅成功，列表与桌面端一致 | P0 | |
| SESS-02 | 会话状态同步 | 列表已加载 | 桌面端修改会话（新建/改名/删除） | 移动端 delta 实时同步 | P0 | |
| CHAT-01 | 流式对话收发 | 会话已打开 | 发送消息，观察回复 | 流式增量渲染（streaming 光标/误判修复回归点 c6e43bc），markdown 正常渲染 | P0 | |
| CHAT-02 | 会话中途断网 | 对话进行中 | 开飞行模式 10s 后恢复 | 进入重连态；恢复后 replay 补齐断线期间消息（web-remote-replayable 语义） | P0 | |
| CHAT-03 | 发送失败回滚 | 对话页 | 构造发送失败（断网发送） | 失败文本回滚为草稿；发送期间用户已输入的新内容不被旧草稿覆盖（评审 P3 回归点） | P1 | |
| CHAT-04 | 发送前未选模型的拦截/提示 | 对话页，且未选择任何模型 | 直接输入并点发送 | App 给出明确提示（如禁用发送或弹"请先选择模型"），不静默放行导致误以为未发送而重复发送（FP-2026-10-19-01 衍生边界） | P1 | NotRun |
| TERM-01 | 终端面板滚动 | 打开终端 | 输出多行后查看 | LazyColumn 行渲染、输出增长时智能贴底；手动上滑查看历史不被强制拉回底部（d866abd 回归点） | P1 | |
| TERM-02 | 动态事件 payload | 打开终端 | 触发桌面输出 | 下行原始字符串 payload 正确渲染，不出现 id 信封嵌套（2f93ba0 回归点） | P0 | |
| SESS-03 | Git 分支选择器 | 会话打开 | 切换分支 | 官方 useGitBranchSwitcher 移动端形态可用（5df1965 回归点） | P1 | |
| SESS-04 | 用量配额页 | 连接 Ready | 查看用量 | 全时段数据 + 模型列表正确（78abb23 回归点） | P2 | |

## 3. UU 远程（feature/uuremote）

| ID | 用例 | 前置 | 步骤 | 预期 | 优先级 | 结果 |
|---|---|---|---|---|---|---|
| UU-01 | 未安装检测 | 设备无 UU 远程 | 触发绑定 | 双路径检测正确提示安装（78abb23 回归点） | P1 | |
| UU-02 | 绑定回调 | 已安装 UU | 发起绑定并跳转授权 | zcode://uuremote/bind 回跳正常，绑定状态落库（UuRemoteDeviceStore） | P0 | |
| UU-03 | 远控回跳 | 已绑定 | 发起远控 | zcode://uuremote/control 拉起 UU 并可回跳 | P1 | |
| UU-04 | 回调覆盖不悬挂 | - | 3 分钟内连续两次绑定请求 | 旧 deferred 被 Cancelled 而非等满超时（评审 P2-8 回归点） | P2 | |

## 4. 后台稳定性与生命周期（app/remote）

| ID | 用例 | 前置 | 步骤 | 预期 | 优先级 | 结果 |
|---|---|---|---|---|---|---|
| BG-01 | 前台服务保活 | 远控页已打开 | 回到桌面 5 分钟 | RemoteControlKeepAliveService 前台通知存在，进程不被回收，屏幕常亮 | P0 | |
| BG-02 | 后台回收恢复 | 无前台服务 | 开发者选项限制后台进程后返回 App | 重启后最近链接可一键重开，状态恢复不崩溃 | P1 | |
| BG-03 | 通知权限拒绝（API 33+） | Android 12+ | 拒绝通知权限后打开远控 | App 正常运行；引导开启通知（Android 12 实测 POST_NOTIFICATIONS 行为） | P1 | |
| BG-04 | 进程被杀重建 | 会话页在前台 | adb shell am kill com.zai.zcode 后重开 | 无残留半开连接；workspace 单一 owner 恢复正确 | P1 | |
| BG-05 | 桌面 bridge-open 引导 | 桌面 host 未就绪 | 移动端连接 | 桌面启动期有界重试后给出明确引导文案（9e9b139/2f43001 回归点） | P1 | |

## 5. 协议层回归（core/protocol，单测执行）

| ID | 用例 | 覆盖点 | 结果 |
|---|---|---|---|
| PROTO-01 | 帧编解码 | 13 字节帧头 (type/id/ack/length u32BE) + 两段序列化 | **Pass**（RpcFrameTest 11 用例，2026-10-19） |
| PROTO-02 | VQL 变长整数边界 | 32 位边界值、undefined 1 字节 tag、布尔/浮点 fallback | **Pass**（RpcSerializationTest 10 用例） |
| PROTO-03 | 帧重组 | CRC32 校验、分片重装、ordinal tombstone、fragment 冲突、帧长上限 1MiB | **Pass**（V4FrameAssemblerTest 4 + WireReassemblyTest 4 用例） |
| PROTO-04 | V4 握手 | helloConversationV4 → clientHello 两段式、capabilities 单向镜像 | **Pass**（ZcodeProtocolV4UnitTest 7 用例） |
| PROTO-05 | 事件机制 | listenEvent 带 args、双动态事件、PROMISE_CANCEL 与 TS [101,id] 同形 | **Pass**（ChannelClientTest 9 + ConversationReducer 4 + SessionsIndexReducer 3 + V4WorkspaceIdentity 4 用例） |
| PROTO-06 | Markdown 渲染 | core/markdown 全部用例 | **Pass**（MdParserTest 16 用例） |
| PROTO-07 | 会话排序/分组/未读派生 | SessionsOrderingTest | **Fail 3/6** → BUG-011 |
| PROTO-08 | 网络层单测 | core/network（RpcFrameTransportTest） | **Blocked** → BUG-010（测试代码编译失败） |

## 6. 兼容性执行记录

| 日期 | 设备 | OS | App 版本 | 通过率 | 阻塞缺陷 |
|---|---|---|---|---|---|
| 2026-10-19 | 华为 JER-AN10 | Android 12 / EMUI 14.2 | v0.2.0 (2) | 单测 72/75（protocol 56 + markdown 16 全过，sessions 3 失败）；真机执行 CONN-05/06/07/08(部分)、BG-04：4 Pass / 1 Fail | BUG-008（CONN-06 Fail） |

### 2026-10-19 真机执行明细（APK 14:41 构建，14:48 安装）

- CONN-05 相机权限拒绝：**Pass**——系统弹窗正常；拒绝后底部红色引导文案；无崩溃。
- CONN-06 deep link zcode://remote：**Fail → BUG-008**（resolver 查不到，am start 报 unable to resolve）。
- CONN-07 https://zcode.z.ai/remote：Pass（App+浏览器双命中）；evil.com 域不被 App 劫持（CONN-08 部分验证）。
- BG-04 进程查杀重建：**Pass**——后台态 am kill 成功（pid 27800→无），重启新进程 28244，无 FATAL，连接页与已存链接恢复。
- 启动速度 / 内存 / 流量 / 电量基线：见 PERFORMANCE.md（已登记 2026-10-19 数据）。
- Blocked（需桌面端在线）：CONN-01/02/03、SESS-01/02、CHAT-01/02/03、TERM-01/02、SESS-03/04、UU-01~04、BG-01/02/05。

## 7. 冒烟回归集合（每次合入必跑）

CONN-01、CONN-06、SESS-01、CHAT-01、TERM-02、BG-01、PROTO 全部单测。
