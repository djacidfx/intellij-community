// "Replace with generated @PublishedApi bridge call '`access$prop`'" "true"

open class ABase {
    protected var prop = 1

    inline fun test() {
        {
            <caret>prop
        }()
    }

    @PublishedApi
    internal var `access$prop`: Int
        get() = prop
        set(value) {
            prop = value
        }
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.replaceWith.ReplaceProtectedToPublishedApiCallFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.replaceWith.ReplaceProtectedToPublishedApiCallFix