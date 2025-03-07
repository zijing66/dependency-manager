package com.github.zijing66.dependencymanager.services

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager

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
    
    /**
     * 获取ContentFactory实例，兼容不同版本的API
     */
    fun getContentFactory(): ContentFactory {
        return try {
            // 尝试使用新版本API (223+)
            if (getIdeBaselineVersion() >= 223) {
                // 使用反射安全地调用getInstance方法
                try {
                    val method = ContentFactory::class.java.getMethod("getInstance")
                    method.invoke(null) as ContentFactory
                } catch (e: Exception) {
                    LOG.warn("Failed to get ContentFactory using getInstance reflection", e)
                    // 尝试使用旧版本API
                    val serviceMethod = ContentFactory::class.java.getMethod("SERVICE")
                    val service = serviceMethod.invoke(null)
                    val instanceMethod = service.javaClass.getMethod("getInstance")
                    instanceMethod.invoke(service) as ContentFactory
                }
            } else {
                // 尝试使用旧版本API
                try {
                    // 反射调用旧版本的静态方法
                    val serviceMethod = ContentFactory::class.java.getMethod("SERVICE")
                    val service = serviceMethod.invoke(null)
                    val instanceMethod = service.javaClass.getMethod("getInstance")
                    instanceMethod.invoke(service) as ContentFactory
                } catch (e: Exception) {
                    LOG.warn("Failed to get ContentFactory using SERVICE reflection", e)
                    // 尝试直接获取服务实例
                    val method = ContentFactory::class.java.getMethod("getInstance")
                    method.invoke(null) as ContentFactory
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to get ContentFactory", e)
            throw RuntimeException("Failed to get ContentFactory", e)
        }
    }
    
    /**
     * 创建工具窗口内容，兼容不同版本的API
     */
    fun createContent(contentManager: ContentManager, component: javax.swing.JComponent, displayName: String, isLockable: Boolean) {
        try {
            val factory = getContentFactory()
            val content = factory.createContent(component, displayName, isLockable)
            contentManager.addContent(content)
        } catch (e: Exception) {
            LOG.error("Failed to create content", e)
        }
    }
} 