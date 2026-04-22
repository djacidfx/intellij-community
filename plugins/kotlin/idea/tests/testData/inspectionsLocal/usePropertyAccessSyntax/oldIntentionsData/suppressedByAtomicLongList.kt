// WITH_STDLIB
// WITH_JDK
// PROBLEM: none
import java.util.concurrent.atomic.AtomicLong

fun main() {
    val l = AtomicLong()
    val x = l.get<caret>AndIncrement()
}