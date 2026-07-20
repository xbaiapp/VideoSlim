package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescedProgressDispatcherTest {
    @Test
    fun `a long copy keeps only one queued handler delivery and emits latest progress`() {
        val queue = ArrayDeque<Runnable>()
        val delivered = mutableListOf<Double>()
        val dispatcher =
            CoalescedProgressDispatcher(
                schedule = queue::addLast,
                deliver = delivered::add,
            )

        repeat(250_000) { index -> dispatcher.update(index / 250_000.0) }

        assertEquals(1, queue.size)
        queue.removeFirst().run()
        assertEquals(listOf(249_999 / 250_000.0), delivered)
        assertEquals(0, queue.size)
    }

    @Test
    fun `an update racing a delivery is rescheduled without a backlog`() {
        val queue = ArrayDeque<Runnable>()
        val delivered = mutableListOf<Double>()
        lateinit var dispatcher: CoalescedProgressDispatcher
        dispatcher =
            CoalescedProgressDispatcher(
                schedule = queue::addLast,
                deliver = { value ->
                    delivered += value
                    if (delivered.size == 1) dispatcher.update(0.9)
                },
            )

        dispatcher.update(0.4)
        queue.removeFirst().run()
        assertEquals(1, queue.size)
        queue.removeFirst().run()

        assertEquals(listOf(0.4, 0.9), delivered)
        assertEquals(0, queue.size)
    }

    @Test
    fun `progress never regresses`() {
        val queue = ArrayDeque<Runnable>()
        val delivered = mutableListOf<Double>()
        val dispatcher = CoalescedProgressDispatcher(queue::addLast, delivered::add)

        dispatcher.update(0.8)
        dispatcher.update(0.2)
        queue.removeFirst().run()

        assertEquals(listOf(0.8), delivered)
    }
}
