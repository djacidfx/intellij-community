// COMPILER_ARGUMENTS: -Xexplicit-backing-fields
interface I
class C {
    val x: List<I>
        field: MutableList<I> = mutableListOf()<caret>

    fun update(newX: I) {
        val x: List<String> = listOf()
        this.x.add(newX)
    }
}