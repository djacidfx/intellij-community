// PROBLEM: none
// WITH_STDLIB

fun foo() {
    val myFoo = Foo().apply {
        <caret>setFirst(10).setSecond("str")
    }
}