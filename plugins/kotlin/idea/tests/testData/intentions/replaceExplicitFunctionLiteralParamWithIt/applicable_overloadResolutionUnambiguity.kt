fun test() {
    foo { <caret>i -> i + 1 }
}

fun foo(f: (Int) -> Int) {}
fun foo(f: (Int, Int) -> Int) {}

// IGNORE_K1
// the intention is inapplicable for K1, see the paired testNotApplicable_overloadResolutionAmbiguity