// "Replace with filter" "false"
// TOOL: org.jetbrains.kotlin.idea.codeInsight.inspections.shared.SuspiciousCollectionReassignmentInspection
// ACTION: Change type to mutable
// ACTION: Join with initializer
// ACTION: Replace overloaded operator with function call
// ACTION: Replace with ordinary assignment
// WITH_STDLIB
fun test() {
    var map = mapOf(1 to 10)
    map <caret>-= listOf(1)
}