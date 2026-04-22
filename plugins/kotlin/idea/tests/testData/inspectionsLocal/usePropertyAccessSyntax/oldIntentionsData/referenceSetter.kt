// COMPILER_ARGUMENTS: -XXLanguage:+ReferencesToSyntheticJavaProperties
// PROBLEM: none
fun main() {
    suppressUnused(Foo::<caret>setFoo)
}

fun suppressUnused(foo: (Foo, Int) -> Unit): Any = foo
