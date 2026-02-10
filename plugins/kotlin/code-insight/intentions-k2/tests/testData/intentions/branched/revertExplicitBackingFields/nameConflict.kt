// COMPILER_ARGUMENTS: -Xexplicit-backing-fields
interface I
class C {
    val x: List<I>
        field: MutableList<I> = mutableListOf()<caret>

    fun update(newX: I) {
        val _x: String = ""
        println(_x)
        x.add(newX)
    }
}