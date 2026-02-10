// COMPILER_ARGUMENTS: -Xexplicit-backing-fields
class Foo {
    val x: List<Any>
        field: MutableList<Int> = mutableListOf()<caret>

    fun returnInt(): Int = x[0]
}
