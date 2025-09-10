import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.locks.ReentrantLock

/**
 * KAA (Kotlin Async/Await) - A lightweight library that brings async/await syntax
 * to Java's CompletableFuture, leveraging Java 21's virtual threads.
 * 
 * This library provides a clean async/await API that simplifies asynchronous programming
 * by eliminating callback hell and making concurrent code more readable and maintainable.
 */
class KAA {
    companion object {
        /**
         * Thread-local context holder that maintains the async execution context.
         * Uses ScopedValue to ensure proper context isolation between async operations.
         */
        private val CONTEXT_HOLDER: ScopedValue<Context> = ScopedValue.newInstance()

        /**
         * Creates an asynchronous operation that runs on a virtual thread.
         * 
         * @param fn The callable containing the async code to execute
         * @return CompletableFuture that will complete with the result of fn
         * @throws Exception if the callable throws an exception, it will be propagated
         *                   through the CompletableFuture as a CompletionException
         */
        fun <A> async(fn: Callable<A>): CompletableFuture<A> =
            CompletableFuture<A>().also { cf ->
                // Start a new virtual thread for each async operation
                Thread.startVirtualThread {
                    Thread.currentThread().name = "async-vt-${Thread.currentThread().threadId()}"
                    try {
                        // Establish scoped context for this async operation
                        ScopedValue.where(CONTEXT_HOLDER, Context()).run {
                            try {
                                // Execute the user's callable and complete the future
                                cf.complete(fn.call())
                            } catch (e: Exception) {
                                // Propagate exceptions through the CompletableFuture
                                cf.completeExceptionally(e)
                            }
                        }
                    } catch (t: Throwable) {
                        // Handle any unexpected throwables
                        cf.completeExceptionally(t)
                    }
                }
            }

        /**
         * Waits for a CompletableFuture to complete and returns its result.
         * This function can only be called within an async block and must be
         * executed on a virtual thread.
         * 
         * @param cf The CompletableFuture to await
         * @return The result of the CompletableFuture once it completes
         * @throws IllegalStateException if not called within an async block
         * @throws RuntimeException if interrupted while waiting
         */
        fun <A> await(cf: CompletableFuture<A>): A =
            CONTEXT_HOLDER
                .orElseThrow { IllegalStateException("await must be called within an async block") }
                .await(cf)
    }

    /**
     * Internal context class that manages the await mechanism for virtual threads.
     * Uses ReentrantLock and condition variables to efficiently park virtual threads
     * until CompletableFutures complete.
     */
    private class Context {
        /** Lock for synchronizing access to the condition variable */
        private val lock = ReentrantLock()
        
        /** Condition variable for parking/unparking the awaiting virtual thread */
        private val cond = lock.newCondition()

        /**
         * Awaits the completion of a CompletionStage and returns its result.
         * This method parks the current virtual thread until the stage completes,
         * then returns the result.
         * 
         * @param stage The CompletionStage to await
         * @return The result of the completed stage
         * @throws IllegalArgumentException if not called from a virtual thread
         * @throws RuntimeException if interrupted while waiting
         */
        fun <A> await(stage: CompletionStage<A>): A {
            // Ensure we're running on a virtual thread for efficient parking
            require(Thread.currentThread().isVirtual) {
                "await must be called from a virtual thread"
            }

            lock.lock()
            return try {
                // Register a completion handler that signals when the stage completes
                stage.whenCompleteAsync { _, _ ->
                    lock.lock()
                    try {
                        cond.signal() // Wake up the waiting virtual thread
                    } finally {
                        lock.unlock()
                    }
                }
                
                // Park the virtual thread until the completion handler signals
                cond.await()
                
                // Return the result (join() will re-throw any exceptions)
                stage.toCompletableFuture().join()
            } catch (e: InterruptedException) {
                // Convert InterruptedException to RuntimeException
                throw RuntimeException(e)
            } finally {
                lock.unlock()
            }
        }
    }
}


