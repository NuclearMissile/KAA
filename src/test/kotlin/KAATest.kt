import KAA.Companion.async
import KAA.Companion.await
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test

class KAATest {
    companion object {
        const val RECURSE_ITERATIONS = 10000L
        val VT_EXECUTOR: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    }

    fun userIdsFromDb(): CompletableFuture<List<Long>> {
        return CompletableFuture.supplyAsync({
            Thread.sleep(1000)
            listOf(1L, 2L, 3L)
        }, VT_EXECUTOR)
    }

    fun userNamesFromSomeApi(userId: Long): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            Thread.sleep(1000)
            "User $userId"
        }, VT_EXECUTOR)
    }

    fun buildPdf(userNames: List<String>): CompletableFuture<ByteArray> {
        return CompletableFuture.supplyAsync { userNames.joinToString().toByteArray() }
    }

    @Test
    fun testSimpleAsyncAwait() {
        val future = async {
            val userIds = await(userIdsFromDb())
            val userNames = userIds
                .map { id -> await(userNamesFromSomeApi(id)) }
                .toList()

            val pdf = await(buildPdf(userNames))

            println("Generated pdf for user ids: $userIds")
            pdf
        }

        assertEquals(22, future.join().size)
    }

    @Test
    fun testSimple() {
        val userPdf = userIdsFromDb().thenCompose { userIds ->
            var userNamesFuture = CompletableFuture.supplyAsync { mutableListOf<String>() }
            for (userId in userIds) {
                userNamesFuture = userNamesFuture.thenCompose { list ->
                    userNamesFromSomeApi(userId).thenApply { userName ->
                        list.add(userName)
                        list
                    }
                }
            }
            userNamesFuture.thenCompose { userNames ->
                buildPdf(userNames).thenApply { pdf ->
                    println("Generated pdf for user ids: $userIds")
                    pdf
                }
            }
        }

        assertEquals(22, userPdf.toCompletableFuture().join().size)
    }

    @Test
    fun testAwaitNesting() {
        val future = async {
            val ids1 = await(userIdsFromDb())
            val ids2 = await(async { await(userIdsFromDb()) })
            ids1.size + ids2.size
        }

        assertEquals(6L, future.join().toLong())
    }

    @Test
    fun testAwaitForLoop() {
        val future = async {
            val userNames = mutableListOf<String>()
            for (userId in 1..10L) {
                userNames.add(await(userNamesFromSomeApi(userId)))
            }
            userNames[userNames.size - 1]
        }

        assertEquals("User 10", future.join())
    }


    @Test
    fun testRecursiveAwait() {
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