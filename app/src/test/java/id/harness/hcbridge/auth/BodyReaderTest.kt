package id.harness.hcbridge.auth

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求头/请求体的**字节级**拆包（计划 Task 5）。
 *
 * 三条不变式：上限按字节算；不按 `Content-Length` 预分配；`read` 读体时 body 仍留在同一个流里。
 */
class BodyReaderTest {

    @Test
    fun readerStopsAtCapWithoutPreallocating() {
        assertNull(BodyReader.read(ByteArrayInputStream(ByteArray(300_000)), declared = 300_000))
        assertEquals(5, BodyReader.read(ByteArrayInputStream("hello".toByteArray()), declared = 5)!!.size)
        assertTrue(BodyReader.declaredTooLarge(262_145))
        assertFalse(BodyReader.declaredTooLarge(null))
    }

    @Test
    fun undeclaredBodyIsCappedToo() {
        assertNull(BodyReader.read(ByteArrayInputStream(ByteArray(BodyReader.CAP + 1)), declared = null))
        assertEquals(
            BodyReader.CAP,
            BodyReader.read(ByteArrayInputStream(ByteArray(BodyReader.CAP)), declared = null)!!.size,
        )
    }

    @Test
    fun negativeAndOversizedDeclaredLengthsAreRejected() {
        assertTrue(BodyReader.declaredTooLarge(-1))
        assertTrue(BodyReader.declaredTooLarge(BodyReader.CAP + 1))
        assertFalse(BodyReader.declaredTooLarge(BodyReader.CAP))
        assertNull(BodyReader.read(ByteArrayInputStream("hi".toByteArray()), declared = -1))
        assertNull(BodyReader.read(ByteArrayInputStream(ByteArray(10)), declared = BodyReader.CAP + 1))
    }

    @Test
    fun declaredLengthBoundsTheRead() {
        // 流里还有更多字节，但声明是 5 ⇒ 只读 5（keep-alive 连接不能读到下一个请求上）
        assertEquals(
            "hello",
            String(BodyReader.read(ByteArrayInputStream("hello world".toByteArray()), declared = 5)!!),
        )
    }

    @Test
    fun headReaderStopsAtBlankLineAndLeavesBodyOnStream() {
        val raw = "POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello"
        val inp = ByteArrayInputStream(raw.toByteArray())
        val head = BodyReader.readHead(inp)!!
        assertTrue(head.endsWith("\r\n\r\n"))
        assertTrue(head.startsWith("POST /mcp HTTP/1.1"))
        assertEquals("hello", String(BodyReader.read(inp, 5)!!))
        assertNull(BodyReader.readHead(ByteArrayInputStream(ByteArray(20_000)), cap = 16_384))
    }

    @Test
    fun headReaderFailsOnTruncatedHead() {
        assertNull(BodyReader.readHead(ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray())))
        assertNull(BodyReader.readHead(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun headReaderAcceptsHeadExactlyAtCap() {
        val prefix = "GET / HTTP/1.1\r\nX: "
        val head = prefix + "a".repeat(BodyReader.HEAD_CAP - prefix.length - 4) + "\r\n\r\n"
        assertEquals(BodyReader.HEAD_CAP, head.length)
        val read = BodyReader.readHead(ByteArrayInputStream(head.toByteArray()))
        assertNotNull(read)
        assertEquals(BodyReader.HEAD_CAP, read!!.length)
    }

    @Test
    fun headReaderRejectsOneByteOverTheCap() {
        // 上界必须是 cap，不能是 cap + 1（多读的那个字节不叫“恰好”）
        val prefix = "GET / HTTP/1.1\r\nX: "
        val head = prefix + "a".repeat(BodyReader.HEAD_CAP + 1 - prefix.length - 4) + "\r\n\r\n"
        assertEquals(BodyReader.HEAD_CAP + 1, head.length)
        assertNull(BodyReader.readHead(ByteArrayInputStream(head.toByteArray())))
    }
}
