package com.intellij.driver.sdk.ui.components.go

import com.intellij.driver.sdk.ui.components.ComponentData
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.toolwindows.ToolWindowUiComponent

fun IdeaFrameUI.goPerformanceToolWindow(action: GoPerformanceToolWindowUI.() -> Unit = {}): GoPerformanceToolWindowUI =
  x(GoPerformanceToolWindowUI::class.java) {
    componentWithChild(
      byClass("InternalDecoratorImpl"),
      byAccessibleName("Go Performance Optimization")
    )
  }.apply(action)

class GoPerformanceToolWindowUI(data: ComponentData) : ToolWindowUiComponent(data) {
  fun viewerTab(name: String): UiComponent =
    x { and(byClass("SimpleColoredComponent"), byAccessibleName(name)) }
}
