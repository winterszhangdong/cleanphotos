package com.winters.cleanphotos.data.datasource

import org.junit.Assert.assertEquals
import org.junit.Test

/** 卸载卷上的记录能不能当作已删除。证据不够就整次不清理。 */
class MediaPresenceRulesTest {

    @Test
    fun `Android 12 及以上只认包含卸载卷的 id`() {
        val ids = setOf(1L, 4L)
        assertEquals(
            MediaPresence(ids, authoritative = true),
            MediaPresenceRules.decide(31, setOf(9L), setOf(8L), ids, setOf("sd"), setOf("primary")),
        )
        assertEquals(
            MediaPresence(emptySet(), authoritative = false),
            MediaPresenceRules.decide(31, setOf(1L), setOf(1L), null, emptySet(), emptySet()),
        )
        assertEquals(
            MediaPresence(emptySet(), authoritative = false),
            MediaPresenceRules.decide(36, emptySet(), null, null, null, null),
        )
    }

    @Test
    fun `Android 11 最近卷都还挂着才用在线 id 加系统垃圾桶`() {
        assertEquals(
            MediaPresence(setOf(1L, 2L), authoritative = true),
            MediaPresenceRules.decide(30, setOf(1L), setOf(2L), null, setOf("sd"), setOf("sd", "primary")),
        )
        assertEquals(
            MediaPresence(emptySet(), authoritative = false),
            MediaPresenceRules.decide(30, setOf(1L), setOf(2L), null, setOf("sd"), setOf("primary")),
        )
        assertEquals(
            MediaPresence(emptySet(), authoritative = false),
            MediaPresenceRules.decide(30, setOf(1L), null, null, setOf("sd"), setOf("sd")),
        )
        assertEquals(
            MediaPresence(emptySet(), authoritative = false),
            MediaPresenceRules.decide(30, setOf(1L), setOf(2L), null, null, setOf("sd")),
        )
        assertEquals(
            MediaPresence(setOf(1L), authoritative = true),
            MediaPresenceRules.decide(30, setOf(1L), emptySet(), null, emptySet(), setOf("primary")),
        )
    }

    @Test
    fun `Android 10 及以下即使 id 对得上也不清理`() {
        assertEquals(
            MediaPresence(emptySet(), authoritative = false),
            MediaPresenceRules.decide(29, setOf(1L), setOf(2L), setOf(1L, 2L), emptySet(), emptySet()),
        )
        assertEquals(
            MediaPresence(emptySet(), authoritative = false),
            MediaPresenceRules.decide(26, setOf(1L), emptySet(), null, null, null),
        )
    }
}
