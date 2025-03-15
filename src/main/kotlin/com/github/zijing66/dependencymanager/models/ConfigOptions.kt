package com.github.zijing66.dependencymanager.models

/**
 * 配置选项类，用于存储各种配置设置
 */
data class ConfigOptions(
    var showPlatformSpecificBinaries: Boolean = false, // 是否显示平台特定的二进制文件
    var showInvalidPackages: Boolean = false, // 是否显示失效的包
    var includeSnapshot: Boolean = false, // 是否包含快照/预发布版本
    var targetPackage: String = "" // 目标包名/组件名
) {
    companion object {
        // 单例模式，确保全局共享相同的配置
        private val instance = ConfigOptions()
        
        fun getInstance(): ConfigOptions {
            return instance
        }
    }
    
    /**
     * 重置所有配置选项到默认值
     */
    fun resetAll() {
        showPlatformSpecificBinaries = false
        showInvalidPackages = false
        includeSnapshot = false
        targetPackage = ""
    }
} 