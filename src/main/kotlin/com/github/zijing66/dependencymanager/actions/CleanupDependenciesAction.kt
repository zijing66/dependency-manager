package com.github.zijing66.dependencymanager.actions

import com.github.zijing66.dependencymanager.services.CompatibilityUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class CleanupDependenciesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // 使用兼容性工具类获取工具窗口
        val toolWindow = CompatibilityUtil.getToolWindow(project, "DependencyManager") ?: return
        
        if (!toolWindow.isVisible) {
            // 使用兼容性工具类显示工具窗口
            CompatibilityUtil.showToolWindow(toolWindow) {
                toolWindow.activate(null)
            }
        } else {
            toolWindow.activate(null)
        }
    }

    override fun update(e: AnActionEvent) {
        // 只在项目中启用这个action
        e.presentation.isEnabled = e.project != null
    }
}