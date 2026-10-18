package com.zai.zcode.feature.sessions

/**
 * 任务 Home 列表的筛选/排序/分组纯函数（docs/ui-design.md Phase B）。
 * 无 Android 依赖，可纯 JVM 单测。
 */
object SessionsOrdering {

    /** 应用筛选 + 排序：需要你 > 运行中 > 置顶 > 时间倒序。 */
    fun apply(
        list: List<SessionsRepository.SessionItem>,
        filter: SessionFilter,
        nowMs: Long = System.currentTimeMillis(),
    ): List<SessionsRepository.SessionItem> {
        val filtered = when (filter) {
            SessionFilter.All -> list
            SessionFilter.NeedsYou -> list.filter { it.hasPendingInteraction }
            SessionFilter.Running -> list.filter { it.isRunning }
        }
        return filtered.sortedWith(
            compareByDescending<SessionsRepository.SessionItem> { it.hasPendingInteraction }
                .thenByDescending { it.isRunning }
                .thenByDescending { it.pinned }
                .thenByDescending { it.lastActivityAt ?: 0L },
        )
    }

    private val SessionsRepository.SessionItem.isRunning: Boolean
        get() = !ended && (phase == "running" || phase == "prewarming" || phase == "draft")

    /**
     * 分组：置顶 → 今天 → 昨天 → 更早（保持传入顺序）。
     * 返回有序段列表；空段不出现。
     *
     * 修复依据（v0.2.23 自检）：原签名带 [startOfDayMs] 回调参数但实现从未使用
     * （天数直接按 activity/DAY_MS 与 nowEpochDay 比较），调用点只能传恒等 lambda，
     * 属遗留死参数，按现语义收窄签名。
     */
    fun groupByRecency(
        list: List<SessionsRepository.SessionItem>,
        nowEpochDay: Long,
    ): List<Section> {
        val sections = linkedMapOf<String, MutableList<SessionsRepository.SessionItem>>()
        for (item in list) {
            val key = when {
                item.pinned -> "置顶"
                else -> {
                    val activity = item.lastActivityAt
                    if (activity == null) "更早"
                    else {
                        val day = activity / DAY_MS
                        when {
                            day >= nowEpochDay -> "今天"
                            day >= nowEpochDay - 1 -> "昨天"
                            else -> "更早"
                        }
                    }
                }
            }
            sections.getOrPut(key) { mutableListOf() }.add(item)
        }
        return sections.map { (title, items) -> Section(title, items) }
    }

    data class Section(val title: String, val items: List<SessionsRepository.SessionItem>)

    private const val DAY_MS = 86_400_000L

    /** 相对时间文案（列表卡右上角）。 */
    fun relativeTime(timestamp: Long?, nowMs: Long = System.currentTimeMillis()): String? {
        if (timestamp == null) return null
        val diff = nowMs - timestamp
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> null // 超过一周回落到绝对日期（UI 层格式化）
        }
    }
}
