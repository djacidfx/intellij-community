// COMPILER_ARGUMENTS: -Xexplicit-backing-fields
class A {
    val town: List<String>
        field: MutableList<String><caret>

    init {
        town = mutableListOf()
    }
}