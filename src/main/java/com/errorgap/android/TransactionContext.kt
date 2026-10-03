package com.errorgap.android

/**
 * The APM transaction running on the current thread, so errors reported
 * during it carry its id as `context.transaction_id` and errorgap links each
 * error to the interaction that raised it.
 *
 * ```
 * val transaction = ApmTransaction(path = "/checkout", durationMs = 0.0)
 * ErrorgapTransactionContext.run(transaction.id) { submitOrder() }
 * ```
 *
 * Thread-local: work that hops threads (a coroutine resuming on another
 * dispatcher) does not carry it; pass the id explicitly there:
 * `NoticeOptions(context = mapOf("transaction_id" to transaction.id))`.
 */
object ErrorgapTransactionContext {
    private val current = ThreadLocal<String?>()

    /** The id of the transaction running on this thread, or null. */
    @JvmStatic
    fun current(): String? = current.get()

    /** Run [block] with [id] as the current transaction; the previous one is restored after. */
    @JvmStatic
    fun <T> run(id: String, block: () -> T): T {
        val previous = current.get()
        current.set(id)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
