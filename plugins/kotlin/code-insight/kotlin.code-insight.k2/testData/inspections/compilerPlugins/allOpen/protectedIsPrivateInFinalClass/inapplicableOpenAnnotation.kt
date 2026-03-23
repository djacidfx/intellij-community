// "'protected' visibility is effectively 'private' in a final class" "true"
// FIX: "Make private"

@Open
class Test {
    <caret>protected fun foo() {
    }
}
