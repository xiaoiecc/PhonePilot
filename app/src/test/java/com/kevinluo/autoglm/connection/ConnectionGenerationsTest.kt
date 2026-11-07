package com.kevinluo.autoglm.connection

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionGenerationsTest {
    @Test fun `same binder repeat delivery preserves its death recipient identity`() {
        val clock = ConnectionGenerations()
        val attempt = clock.beginAttempt()
        val binderIdentity = Any()
        val first = requireNotNull(clock.beginBinding(attempt, binderIdentity))
        val repeated = clock.beginBinding(attempt, binderIdentity)
        assertEquals(first, repeated)
        assertTrue(clock.isCurrent(first))
    }

    @Test fun `newest queued refresh owns connection work`() {
        val clock = ConnectionGenerations()
        val first = clock.supersedeEvents()
        val second = clock.supersedeEvents()
        assertFalse(clock.isCurrent(first))
        assertTrue(clock.isCurrent(second))
    }

    @Test fun `queued global death cannot detach a subsequently connected attempt`() {
        val clock = ConnectionGenerations()
        clock.beginAttempt()
        val death = clock.capture()
        clock.invalidate()
        val next = clock.beginAttempt()
        val binding = requireNotNull(clock.beginBinding(next))
        clock.supersedeEvents()
        assertFalse(clock.isCurrent(death))
        assertTrue(clock.isCurrent(binding))
    }

    @Test fun `timeout invalidates every callback and death from its expired attempt`() {
        val clock = ConnectionGenerations()
        val old = clock.beginAttempt()
        val delivery = requireNotNull(clock.beginBinding(old))
        clock.invalidate()
        val next = clock.beginAttempt()
        assertFalse(clock.isCurrent(old))
        assertFalse(clock.isCurrent(delivery))
        assertNull(clock.beginBinding(old))
        assertTrue(clock.isCurrent(next))
    }

    @Test fun `late old connection delivery and disconnect cannot affect newer binder in same attempt`() {
        val clock = ConnectionGenerations()
        val attempt = clock.beginAttempt()
        val old = requireNotNull(clock.beginBinding(attempt))
        val disconnected = clock.currentBinding()
        val replacement = requireNotNull(clock.beginBinding(attempt))
        assertFalse(clock.isCurrent(old))
        assertFalse(clock.isCurrent(requireNotNull(disconnected)))
        assertTrue(clock.isCurrent(replacement))
        assertTrue(clock.isCurrent(attempt))
    }

    @Test fun `foreground refresh supersedes retry but keeps existing binder callbacks valid`() {
        val clock = ConnectionGenerations()
        val attempt = clock.beginAttempt()
        val binding = requireNotNull(clock.beginBinding(attempt))
        val pending = clock.capture()
        clock.supersedeEvents()
        assertFalse(clock.isCurrent(pending))
        assertTrue(clock.isCurrent(attempt))
        assertTrue(clock.isCurrent(binding))
    }

    @Test fun `retry cannot clear or connect after another attempt took ownership`() {
        val clock = ConnectionGenerations()
        clock.beginAttempt()
        val retry = clock.invalidate()
        val next = clock.beginAttempt()
        assertFalse(clock.isCurrent(retry))
        assertTrue(clock.isCurrent(next))
    }
}
