import KAA.Companion.async
import KAA.Companion.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * Test class for the KAA async/await library functionality.
 * Tests various scenarios including concurrent execution, recursive calls, and performance comparisons.
 */
class KAATest {
    companion object {
        // Number of iterations for recursive test cases
        const val RECURSE_ITERATIONS = 10000L
        // Virtual thread executor for IO bond operations
        val VT_EXECUTOR: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
        // Thread pool executor for CPU bond operations
        val TP_EXECUTOR: ExecutorService = Executors.newCachedThreadPool()

        /**
         * Simulates fetching user IDs from a database or API.
         * Includes a 1-second delay to simulate I/O operations.
         * @return List of user IDs (1, 2, 3)
         */
        fun findUserIds(): List<Long> {
            Thread.sleep(1000)
            printThreadName("findUserIds...")
            return listOf(1L, 2L, 3L)
        }

        /**
         * Simulates fetching a user name based on user ID.
         * Includes a 1-second delay to simulate I/O operations.
         * @param userId The ID of the user to fetch
         * @return The user name as a string
         */
        fun fetchUserName(userId: Long): String {
            Thread.sleep(1000)
            printThreadName("fetchUserName for userId: $userId")
            return "User $userId"
        }

        /**
         * Simulates encrypting a user name with CPU-intensive work.
         * Uses busy waiting for 500ms to simulate encryption processing.
         * @param userName The user name to encrypt
         * @return The encrypted user name with "encrypted: " prefix
         */
        fun encryptUserName(userName: String): String {
            val startNano = System.nanoTime()
            while (System.nanoTime() - startNano < 5e8) {
            } // Busy wait for 500ms
            printThreadName("encrypt for userName: $userName")
            return "encrypted: $userName"
        }

        /**
         * Utility function to print the current thread name with a message.
         * Formats virtual threads with "vt-" prefix and thread ID.
         * @param message Optional message to print along with thread name
         */
        fun printThreadName(message: String = "") {
            val threadName = Thread.currentThread().name.ifEmpty { "vt-${Thread.currentThread().threadId()}" }
            println("$threadName: $message")
        }
    }
    
    /**
     * Tests exception handling in async blocks.
     * Verifies that exceptions thrown within async blocks are properly wrapped
     * in CompletionException when the CompletableFuture is joined.
     */
    @Test
    fun testException() {
        val future = async { throw Exception("Test exception") }
        
        assertThrows<CompletionException> { future.join() }
    }

    /**
     * Tests the KAA async/await functionality with a complex workflow.
     * 1. Fetches user IDs asynchronously
     * 2. Maps each ID to fetch user names concurrently
     * 3. Encrypts each user name and joins results
     * Verifies that the async/await syntax produces expected results.
     */
    @Test
    fun testWithAsyncAwait() {
        val future = async {
            val userIds = async { findUserIds() }
            val userNames = await(userIds).map { id -> async { fetchUserName(id) } }
            userNames.map {
                val name = await(it)
                CompletableFuture.supplyAsync({ encryptUserName(name) }, TP_EXECUTOR)
            }.joinToString { await(it) }
        }

        assertEquals("encrypted: User 1, encrypted: User 2, encrypted: User 3", future.join())
    }

    /**
     * Equivalent test using raw CompletableFuture API for comparison.
     * Performs the same workflow as testWithAsyncAwait but uses
     * CompletableFuture.thenCompose() and thenApply() chains.
     * Demonstrates how KAA simplifies asynchronous code.
     */
    @Test
    fun testWithCF() {
        val future = CompletableFuture.supplyAsync({ findUserIds() }, VT_EXECUTOR)
            .thenCompose { ids ->
                val nameFutures = ids.map { id ->
                    CompletableFuture.supplyAsync({ fetchUserName(id) }, VT_EXECUTOR)
                }
                CompletableFuture.allOf(*nameFutures.toTypedArray())
                    .thenApply { nameFutures.map { it.join() } }
            }
            .thenCompose { names ->
                val encryptedFutures = names.map { name ->
                    CompletableFuture.supplyAsync({ encryptUserName(name) }, TP_EXECUTOR)
                }
                CompletableFuture.allOf(*encryptedFutures.toTypedArray())
                    .thenApply { encryptedFutures.joinToString { it.join() } }
            }

        assertEquals("encrypted: User 1, encrypted: User 2, encrypted: User 3", future.join())
    }
    
    /**
     * Tests concurrent execution of 1 million async operations using KAA await.
     * Creates a large number of concurrent async tasks and verifies that they
     * all complete successfully. This tests the library's scalability and
     * ability to handle massive concurrent workloads.
     */
    @Test
    fun testConcurrentAwait() {
        val future = async { (1..1e6.toLong()).map { async { fetchUserName(it) } }.map(::await) }

        assertEquals("User ${1e6.toLong()}", future.join().last())
    }
    
    /**
     * Equivalent concurrent test using raw CompletableFuture API for comparison.
     * Creates 1 million concurrent CompletableFutures and waits for all to complete.
     * This serves as a performance baseline to compare against the KAA library
     * and demonstrates the difference in code complexity.
     */
    @Test
    fun testConcurrentCF() {
        val futures = (1..1e6.toLong()).map { CompletableFuture.supplyAsync({ fetchUserName(it) }, VT_EXECUTOR) }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        
        assertEquals("User ${1e6.toLong()}", futures.last().join())
    }

    /**
     * Tests recursive async operations with deep call stacks.
     * Recursively calls async functions 10,000 times to verify
     * that the library handles deep recursion without stack overflow.
     * This tests the library's ability to handle complex async chains.
     */
    @Test
    fun testRecursiveAwait() {
        /**
         * Recursive helper function that creates a chain of async operations.
         * @param iterations Number of remaining iterations
         * @param result Current accumulated result
         * @return CompletableFuture containing the final result
         */
        fun recurseAsync(iterations: Long, result: Long): CompletableFuture<Long> {
            return async {
                if (iterations == 0L) {
                    result
                } else {
                    val newResult = await(completedFuture(result + 1))
                    await(recurseAsync(iterations - 1, newResult))
                }
            }
        }

        val future = recurseAsync(RECURSE_ITERATIONS, 0)

        assertEquals(RECURSE_ITERATIONS, future.join())
    }
}