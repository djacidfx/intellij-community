// WITH_STDLIB
// RUNTIME_WITH_FULL_JDK
// PROBLEM: none
import java.nio.ByteBuffer

fun main(args: Array<String>) {
    val bb = ByteBuffer.allocate(16)
    val value = bb.getShort()<caret>
}