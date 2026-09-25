package az.petek.llm.infrastructure.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class StreamDrainTest {
    private val bytes = ByteArray(100_000) { (it % 251).toByte() }

    @Test
    fun `head keeps the leading bytes and still reads to the end`() {
        val input = ByteArrayInputStream(bytes)

        StreamDrain.head(input, limit = 10) shouldBe bytes.copyOfRange(0, 10)
        input.available() shouldBe 0
    }

    @Test
    fun `head returns everything below the limit`() {
        StreamDrain.head(ByteArrayInputStream(bytes), limit = 1_000_000) shouldBe bytes
    }

    @Test
    fun `tail keeps only the last bytes`() {
        StreamDrain.tail(ByteArrayInputStream(bytes), limit = 300) shouldBe bytes.copyOfRange(bytes.size - 300, bytes.size)
    }

    @Test
    fun `tail returns everything below the limit`() {
        StreamDrain.tail(ByteArrayInputStream(bytes.copyOf(50)), limit = 300) shouldBe bytes.copyOf(50)
    }

    @Test
    fun `a pipe that breaks mid-read yields what arrived before`() {
        val breaking =
            object : InputStream() {
                private var served = 0

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (served > 0) throw IOException("Stream closed")
                    served = 3
                    b[off] = 1
                    b[off + 1] = 2
                    b[off + 2] = 3
                    return 3
                }
            }

        StreamDrain.head(breaking, limit = 10) shouldBe byteArrayOf(1, 2, 3)
    }
}
