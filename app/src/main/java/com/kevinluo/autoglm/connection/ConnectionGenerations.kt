package com.kevinluo.autoglm.connection

/** Thread-safe identity capture for callbacks queued from binder threads onto Main. */
internal class ConnectionGenerations {
    data class Event(val revision: Long)
    data class Attempt(val id: Long)
    data class Binding(val attempt: Attempt, val id: Long)
    private var revision = 0L
    private var nextAttempt = 0L
    private var attempt: Attempt? = null
    private var nextBinding = 0L
    private var binding: Binding? = null
    private var bindingIdentity: Any? = null

    @Synchronized fun capture(): Event = Event(revision)
    @Synchronized fun supersedeEvents(): Event = Event(++revision)
    @Synchronized fun beginAttempt(): Attempt {
        revision++
        binding = null
        bindingIdentity = null
        return Attempt(++nextAttempt).also { attempt = it }
    }
    @Synchronized fun invalidate(): Event {
        attempt = null
        binding = null
        bindingIdentity = null
        return Event(++revision)
    }
    @Synchronized fun isCurrent(event: Event): Boolean = event.revision == revision
    @Synchronized fun isCurrent(candidate: Attempt): Boolean = candidate == attempt
    @Synchronized fun beginBinding(candidate: Attempt, identity: Any? = Any()): Binding? {
        if (candidate != attempt) return null
        if (identity != null && identity === bindingIdentity) return binding
        bindingIdentity = identity
        return Binding(candidate, ++nextBinding).also { binding = it }
    }
    @Synchronized fun currentBinding(): Binding? = binding
    @Synchronized fun isCurrent(candidate: Binding): Boolean = candidate.attempt == attempt && candidate == binding
}
