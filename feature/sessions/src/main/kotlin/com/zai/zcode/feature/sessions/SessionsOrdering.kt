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
     * 分组：置顶 → 今天 → 昨天 → 更早（固定段序，与文档注释/SessionsOrderingTest
     * 期望一致；组内保持传入顺序）。
     * 返回有序段列表；空段不出现。
     *
     * 修复依据（BUG-011，回归 2026-10-19）：34a9e77 前实现按「首次出现顺序」建段
     * （linkedMap 插入序），列表里昨天的会话排在置顶卡前面时，段序就变成
     * [昨天,置顶,今天,更早]，与注释承诺的固定序不符。改为按固定段序输出，
     * 组内成员仍保持传入顺序（时间倒序由调用方 [apply] 排好）。
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
        // 按固定段序输出（空段跳过），不再依赖插入序
        return SECTION_ORDER.mapNotNull { title ->
            sections[title]?.let { Section(title, it) }
        }
    }

    /** 分组固定段序（BUG-011）：置顶 → 今天 → 昨天 → 更早。 */
    private val SECTION_ORDER = listOf("置顶", "今天", "昨天", "更早")

    data class Section(val title: String, val items: List<SessionsRepository.SessionItem>)

    private const val DAY_MS = 86_400_000L

    /** 相对时间文案（列表卡右上角）。 */
    fun relativeTime(timestamp: Long?, nowMs: Long = System.currentTimeMillis()): String? {
        if (timestamp == null) return null
        val diff = nowMs - timestamp
        // 修复依据（BUG-011，回归 2026-10-19）：负 diff（未来时间戳，如调用方传了
        // 溢出/错误的 activity）原会落进第一个桶显示「刚刚」，语义是错的——未来
        // 的时间没有「X 前」文案，与超一周一样回落到绝对日期。
        return when {
            diff < 0L -> null
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> null // 超过一周回落到绝对日期（UI 层格式化）
        }
    }
}
