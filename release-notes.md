## 手机端审批放行（ApprovalDock）

远程操控的典型姿势是**人不在电脑前**。命令卡在审批上就永远不动了——
这一版让手机能直接放行，把产品从"能看"变成"能用"。

### 新增

- **审批 Dock**：会话页输入栏上方呈现当前待决项（授权 / 需要你输入 / 需要确认），
  纵向排列服务端下发的选项，敏感输入走密码框且不落草稿
- **`resolveInteraction` 命令**：只提交服务端给的 `optionId`，不硬编码 allow/deny；
  服务端返回 `alreadyResolved`（别人在桌面先处理了）按成功对待，卡片撤掉而不是弹错
- 多待决**逐个步进**，一次只呈现一个，不做"全部放行"——一次误触不该交出整轮权限
- 设计令牌新增 `onAttention`：attention 填充态上的前景色，深浅两侧分别配对，不靠猜

### 修复

- `ChatRepository` 缺 `JsonObjectBuilder.put` 导入导致的编译失败（run 62）
- `ApprovalDock` 去掉 `SizeTransform.using`（Compose BOM 2026.02 已非稳定 API），
  改显式 `contentKey` 按 interactionId 判定换卡
- `sendText` / `stop` / `resolveInteraction` 统一补传 `workspaceIdentity`

### 说明

- 继 v0.2.17（会话列表四态 + 退避重试 + 创建失败原因透出）后的第 2 版迭代
- 发的是 debug 签名预览包，暂无 keystore 配置

🤖 Generated with [Claude Code](https://claude.com/claude-code)
