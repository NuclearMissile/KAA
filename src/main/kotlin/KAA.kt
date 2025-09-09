import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.locks.ReentrantLock

class KAA {
    companion object {
        private val AWAIT_CONTEXT: ScopedValue<Cont> = ScopedValue.newInstance()

        fun <A> async(fn: Callable<A>): CompletableFuture<A> =
            CompletableFuture<A>().also { cf ->
                Thread.startVirtualThread {
                    try {
                        ScopedValue.where(AWAIT_CONTEXT, Cont()).run {
                            try {
                                cf.complete(fn.call())
                            } catch (e: Exception) {
                                cf.completeExceptionally(e)
                            }
                        }
                    } catch (t: Throwable) {
                        cf.completeExceptionally(t)
                    }
                }
            }

        fun <A> await(cf: CompletableFuture<A>): A =
            AWAIT_CONTEXT
                .orElseThrow { IllegalStateException("await must be called within an async block") }
                .await(cf)
    }

    private class Cont {
        private val lock = ReentrantLock()
        private val cond = lock.newCondition()

        fun <A> await(stage: CompletionStage<A>): A {
            require(Thread.currentThread().isVirtual) {
                "await must be called from a virtual thread"
            }

            lock.lock()
            return try {
                stage.whenCompleteAsync { _, _ ->
                    lock.lock()
                    try {
                        cond.signal()
                    } finally {
                        lock.unlock()
                    }
                }
                cond.await()
                stage.toCompletableFuture().join()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            } finally {
                lock.unlock()
            }
        }
    }
}


