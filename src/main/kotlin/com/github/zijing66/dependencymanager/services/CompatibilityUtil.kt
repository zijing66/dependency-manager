package com.github.zijing66.dependencymanager.services

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 兼容性工具类，用于处理不同IntelliJ IDEA版本的API差异
 */
object CompatibilityUtil {
    private val LOG = logger<CompatibilityUtil>()
    
    /**
     * 获取当前IDE的基线版本号
     */
    fun getIdeBaselineVersion(): Int {
        return try {
            ApplicationInfo.getInstance().build.baselineVersion
        } catch (e: Exception) {
            LOG.warn("Failed to get IDE baseline version", e)
            // 默认返回一个较新的版本
            223
        }
    }
    
    /**
     * 获取工具窗口，兼容不同版本的API
     */
    fun getToolWindow(project: Project, id: String): ToolWindow? {
        return try {
            ToolWindowManager.getInstance(project).getToolWindow(id)
        } catch (e: Exception) {
            LOG.warn("Failed to get tool window using standard API", e)
            try {
                // 尝试使用反射获取
                val manager = ToolWindowManager.getInstance(project)
                val method = manager.javaClass.getMethod("getToolWindow", String::class.java)
                method.invoke(manager, id) as? ToolWindow
            } catch (e: Exception) {
                LOG.error("Failed to get tool window using reflection", e)
                null
            }
        }
    }
    
    /**
     * 显示工具窗口，兼容不同版本的API
     */
    fun showToolWindow(toolWindow: ToolWindow, callback: (() -> Unit)? = null) {
        try {
            if (getIdeBaselineVersion() >= 223) {
                // 新版本API
                toolWindow.show {
                    callback?.invoke()
                }
            } else {
                // 旧版本API
                toolWindow.show(null)
                callback?.invoke()
            }
        } catch (e: Exception) {
            LOG.warn("Failed to show tool window", e)
            try {
                // 最基本的方法
                toolWindow.show(null)
                callback?.invoke()
            } catch (e: Exception) {
                LOG.error("Failed to show tool window using basic method", e)
            }
        }
    }
} 