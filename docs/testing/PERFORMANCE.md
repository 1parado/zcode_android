# ZCode Android 基础性能测试手册

> 目标：建立 v0.2.0 性能基线；每次发版对比，劣化 >10% 需归因。
> 环境：真机 USB 调试；测试前关闭无关后台 App；每组数据重复 3 次取中位数。

## 1. 启动速度

**口径**：冷启动 TotalTime（进程不存在 → 首帧）。测试前先杀进程。

```bash
adb shell am force-stop com.zai.zcode
adb shell am start -W -n com.zai.zcode/.MainActivity
# 重复 5 次，取中位数；同时观察 logcat 的 Displayed 行
adb logcat -d | grep -E "Displayed com.zai.zcode"
```

| 版本 | 冷启动 TotalTime（中位） | 样本 | 备注 |
|---|---|---|---|
| v0.2.0 (2) | **1385ms** ✅ | 1415/1385/1381/1374/1369（另首跑 1463） | JER-AN10 / Android 12 / 2026-10-19 |

**判据**：冷启动 ≤ 2000ms（Compose + Hilt 单 Activity 首屏），超过需排查 Application/首屏组合函数。

## 2. 内存占用

**口径**：连接会话页空闲、流式对话中、终端大量输出三个场景的 PSS。

```bash
# 场景化采集（连接/对话/终端各采一次）
adb shell dumpsys meminfo com.zai.zcode
# 关注行：TOTAL PSS、Java Heap、Native Heap、Graphics
# WebView 远控场景额外看：
adb shell dumpsys meminfo com.zai.zcode | grep -i webview
```

| 版本 | 空闲 PSS | 对话中 PSS | 远控 WebView PSS | 备注 |
|---|---|---|---|---|
| v0.2.0 (2) | **129MB** ✅（Java 12.3 / Native 16.5 / Graphics 4.7，TOTAL RSS 259MB） | 待测 | 待测 | 2026-10-19，未连接桌面端 |

**判据**：空闲 ≤ 250MB；对话/终端长时间输出后无持续增长（采 10 分钟间隔两点，增长 > 50MB 视为疑似泄漏）。

## 3. 耗电量

**口径**：前台使用 30 分钟的电量消耗 + 后台（前台服务保活）1 小时消耗。

```bash
adb shell dumpsys batterystats --reset        # 归零统计
# ... 执行场景（前台使用 30min / 保活后台 1h）...
adb shell dumpsys batterystats com.zai.zcode > batterystats.txt
# 关注：Uid cpu、Foreground services、Wake locks、网络电量
adb shell dumpsys battery                     # 当前电量/充电状态
```

**注意**：真机必须拔掉 USB 再跑耗电场景（充电状态会污染数据）；可先 `adb shell dumpsys battery set status 3` 模拟放电、`dumpsys battery reset` 恢复。

**判据**：前台使用 30 分钟的电量消耗 + 后台（前台服务保活）1 小时消耗。

**基线（2026-10-19，USB 充电态仅供参考，正式耗电测试需拔线放电场景）**：
- App UID u0a609 估算耗电 **0.59mAh**（全部为 wifi），占整机比例极小；全局大头是屏幕（4m32s 亮屏 10.9mAh）。
- 应用持有的 Wake lock 仅 `*alarm*` 与 datatransport JobScheduler，**无持续性 partial wake lock** ✅（BG-01 场景需在远控页打开后复测前台服务的 Wake lock）。
- 本次统计窗口内未发现 Foreground services 段 → 保活服务未在窗口内运行，需在 BG-01 执行时补测。

| 版本 | 前台 30min | 保活后台 1h | Wake lock | 备注 |
|---|---|---|---|---|
| v0.2.0 (2) | 待测（需拔线） | 待测（需拔线） | 无异常 | |

## 4. 流量

**口径**：按应用 UID 统计 WiFi/蜂窝收发字节。

```bash
UID=$(adb shell dumpsys package com.zai.zcode | grep -m1 "userId=" | sed 's/.*userId=//;s/[^0-9].*//')
adb shell cat /proc/net/xt_qtaguid/stats | grep "^$UID"   # API<30 备用
adb shell dumpsys netstats detail | grep -A5 "uid=$UID"
# 场景化：对话 10 分钟 / 终端滚动 5 分钟，前后差值即场景流量
```

**判据**：空闲连接态心跳流量 ≤ 5MB/h；流式对话流量与输出量级匹配（无重复拉取/泄漏式重连）。

**基线（2026-10-19，UID u0a609，WiFi「ZZU-WLAN」）**：装机以来累计 rx 5.8MB / tx 2.3MB；近 2h 空闲桶 rx 6.4KB / tx 6.9KB —— 心跳流量远低于判据 ✅。对话/终端场景流量待连接桌面端后补测。

## 5. 辅助观测

```bash
# 卡顿/掉帧（Compose 重负载场景：终端快速滚动）
adb shell dumpsys gfxinfo com.zai.zcode framestats
# ANR / 崩溃监控（整个测试期间保持）
adb logcat -v time *:E | grep -E "ANR|FATAL|com.zai.zcode"
```
