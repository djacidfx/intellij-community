// "Remove explicitly specified return type of called function 'A.component2'" "true"
abstract class A {
    abstract operator fun component1(): Int
    abstract operator fun component2(): Int
}

fun foo(a: A) {
    val (w: Int, x: Unit) = a<caret>
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ChangeCallableReturnTypeFix$ForCalled
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.ChangeTypeQuickFixFactories$UpdateTypeQuickFix