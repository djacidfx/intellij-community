// "Replace scope function with safe (?.) call" "false"
// ACTION: Add non-null asserted (a!!) call
// ACTION: Introduce local variable
// ACTION: Replace with safe (?.) call
// ACTION: Surround with null check
// ERROR: Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type String?
// WITH_STDLIB
fun foo(a: String?) {
    a<caret>.length
}