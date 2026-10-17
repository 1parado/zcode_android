package com.zai.zcode.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 任务 Home 筛选/排序/分组纯函数单测（Phase B 计划）。
 */
class SessionsOrderingTest {

    private fun item(
        id: String,
        phase: String? = null,
        ended: Boolean = false,
        activity: Long = 0L,
        permission: Int = 0,
        userInput: Int = 0,
        pinned: Boolean = false,
    ) = SessionsRepository.SessionItem(
        sessionId = id,
        title = id,
        phase = phase,
        ended = ended,
        lastActivityAt = activity,
        preview = null,
        permissionCount = permission,
        userInputCount = userInput,
        pinned = pinned,
    )

    @Test
    fun `排序 需要你 优先于 运行中 优先于 时间倒序`() {
        val list = listOf(
            item("old-running", phase = "running", activity = 100),
            item("pending", permission = 1, activity = 10),
            item("recent", activity = 200),
        )
        val out = SessionsOrdering.apply(list, SessionFilter.All)
        assertEquals(listOf("pending", "old-running", "recent"), out.map { it.sessionId })
    }

    @Test
    fun `筛选 需要你 只留有待决交互`() {
        val list = listOf(
            item("a", permission = 1),
            item("b", userInput = 2),
            item("c", phase = "running"),
        )
        val out = SessionsOrdering.apply(list, SessionFilter.NeedsYou)
        assertEquals(setOf("a", "b"), out.map { it.sessionId }.toSet())
    }

    @Test
    fun `筛选 运行中 含 draft 与 prewarming 不含已结束`() {
        val list = listOf(
            item("a", phase = "running"),
            item("b", phase = "prewarming"),
            item("c", phase = "draft"),
            item("d", phase = "completedSuccess", ended = true),
            item("e"),
        )
        val out = SessionsOrdering.apply(list, SessionFilter.Running)
        assertEquals(setOf("a", "b", "c"), out.map { it.sessionId }.toSet())
    }

    @Test
    fun `分组 置顶在最前 然后今天昨天更早`() {
        val nowDay = 20_000L
        val list = listOf(
            item("yesterday", activity = (nowDay - 1) * 86_400_000L + 1000),
            item("pinned", activity = 1, pinned = true),
            item("today", activity = nowDay * 86_400_000L + 1000),
            item("old", activity = (nowDay - 30) * 86_400_000L),
        )
        val sections = SessionsOrdering.groupByRecency(list, { it }, nowDay)
        assertEquals(listOf("置顶", "今天", "昨天", "更早"), sections.map { it.title })
        assertEquals("pinned", sections[0].items.single().sessionId)
    }

    @Test
    fun `未读派生 lastActivityAt 大于本地水位`() {
        val seen = mapOf("read" to SessionLocalStateStore.LocalState(lastSeenActivityAt = 50))
        val list = listOf(
            item("read", activity = 60),
            item("fresh", activity = 40),
        )
        // 与 republish 相同的派生逻辑
        val unread = list.filter { item ->
            val l = seen[item.sessionId]
            (item.lastActivityAt ?: 0) > (l?.lastSeenActivityAt ?: 0L)
        }
        assertEquals(listOf("read"), unread.map { it.sessionId })
    }

    @Test
    fun `相对时间 桶`() {
        val now = 1_000_000L
        assertEquals("刚刚", SessionsOrdering.relativeTime(now - 30_000, now))
        assertEquals("5 分钟前", SessionsOrdering.relativeTime(now - 5 * 60_000, now))
        assertEquals("3 小时前", SessionsOrdering.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2 天前", SessionsOrdering.relativeTime(now - 2 * 86_400_000, now))
        assertEquals(null, SessionsOrdering.relativeTime(now - 30 * 86_400_000, now))
    }
}
