// COMPILER_ARGUMENTS: -Xcontext-parameters
// LANGUAGE_VERSION: 2.2
class MyClass {
    val value: Int = 42

    context(para<caret>m1: String)
    fun doSomething(param: Int) {
        println("Value: $value, Context Param: $param1, Param: $param")
        withContext()
    }

    fun inside(param: String) {
        with(param) {
            doSomething()
        }
    }

    fun inside1(param: String) {
        with (param) {
            doSomething()
        }
    }

    fun inside2(param: String, another: MyClass) {
        with (param) {
            with(another) {
                doSomething()
            }
        }
    }
}

context(s: String)
fun withContext() {}

fun MyClass.foo() {
    with("param1") {
        doSomething()
    }
}

fun String.bar(m: MyClass) {
    with(m) { doSomething() }
}

class Bar {
    fun String.bar(m: MyClass) {
        with(m) { doSomething() }
    }
}

fun usage() {
    val obj = MyClass()
    with ("test") {
        with(obj) { doSomething() }
    }

    with(obj) {
        with("inside with") {
            doSomething()
        }
    }
}