package net.thunderbird.core.common.collections

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import kotlin.test.Test
import net.thunderbird.core.common.net.HostNameUtils

/** One test per pooled candidate; a failure means the candidate's claim holds on main. */
class StudyCandidatesTest {
    @Test
    fun `T1 removeAll through the iterator removes every matching element`() {
        val queue = minPriorityQueueOf(listOf(1, 10, 2, 11, 12, 3))
        queue.removeAll(listOf(11, 3))
        assertThat(queue.toList().sorted()).isEqualTo(listOf(1, 2, 10, 12))
    }

    @Test
    fun `T2 a second iterator remove without next fails and removes nothing`() {
        val queue = minPriorityQueueOf(listOf(1, 2, 3, 4))
        val iterator = queue.iterator()
        iterator.next()
        iterator.next()
        iterator.next()
        iterator.remove()
        assertFailure { iterator.remove() }.isInstanceOf<IllegalStateException>()
        assertThat(queue.size).isEqualTo(3)
    }

    @Test
    fun `T7 an IPv6 address with eight groups and a double colon is rejected`() {
        assertThat(HostNameUtils.isLegalIPv6Address("1:2:3:4::5:6:7:8")).isNull()
    }

    @Test
    fun `T8 element on an empty queue throws NoSuchElementException`() {
        assertFailure { minPriorityQueueOf<Int>().element() }.isInstanceOf<NoSuchElementException>()
    }
}
