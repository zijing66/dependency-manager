package com.github.zijing66.dependencymanager.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindow

class CleanupDependenciesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow: ToolWindow = toolWindowManager.getToolWindow("DependencyManager") ?: return

        if (!toolWindow.isVisible) {
            toolWindow.show {
                // 可选的回调，在工具窗口显示后执行
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