package com.github.zijing66.dependencymanager.models

/**
 * 依赖类型枚举
 * 使用标准enum class定义，避免使用kotlin.enums包
 * 这样可以提高与旧版本IDE的兼容性
 */
enum class DependencyType {
    MAVEN,
    GRADLE,
    NPM,
    UNKNOWN;
    
    companion object {
        /**
         * 获取所有依赖类型的安全方法
         * 避免直接使用kotlin.enums包中的方法
         */
        @JvmStatic
        fun safeValues(): Array<DependencyType> {
            return values()
        }
        
        /**
         * 根据名称安全获取依赖类型
         * 避免直接使用kotlin.enums包中的方法
         */
        @JvmStatic
        fun safeValueOf(name: String): DependencyType {
            return try {
                valueOf(name)
            } catch (e: Exception) {
                UNKNOWN
            }
        }
    }
} 