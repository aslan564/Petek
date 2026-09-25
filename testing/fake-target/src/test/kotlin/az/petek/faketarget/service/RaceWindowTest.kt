package az.petek.faketarget.service

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RaceWindowTest {
    private val window = RaceWindow(2.seconds)

    @Test
    fun `a lone caller waits the whole window and then goes on`() =
        runTest {
            window.await("t1")
            currentTime shouldBe 2_000
        }

    @Test
    fun `a partner releases the waiting caller at once`() =
        runTest {
            val first = async { window.await("t1").let { currentTime } }
            testScheduler.advanceTimeBy(500)
            window.await("t1")
            currentTime shouldBe 500
            first.await() shouldBe 500
        }

    @Test
    fun `different keys do not meet`() =
        runTest {
            val first = async { window.await("t1").let { currentTime } }
            val second = async { window.await("t2").let { currentTime } }
            first.await() shouldBe 2_000
            second.await() shouldBe 2_000
        }

    @Test
    fun `a waiter that gives up does not release the next caller early`() =
        runTest {
            val quitter = launch { window.await("t1") }
            testScheduler.advanceTimeBy(100)
            quitter.cancelAndJoin()
            val start = currentTime
            window.await("t1")
            currentTime - start shouldBe 2_000
        }

    @Test
    fun `after a pair has met a new caller waits for a new partner`() =
        runTest {
            val first = async { window.await("t1") }
            testScheduler.advanceTimeBy(100)
            window.await("t1")
            first.await()
            val start = currentTime
            window.await("t1")
            currentTime - start shouldBe 2_000
        }
}
