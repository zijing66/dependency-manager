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
            return arrayOf(MAVEN, GRADLE, NPM, UNKNOWN)
        }
        
        /**
         * 根据名称安全获取依赖类型
         * 避免直接使用kotlin.enums包中的方法
         */
        @JvmStatic
        fun safeValueOf(name: String): DependencyType {
            return try {
                when (name.uppercase()) {
                    "MAVEN" -> MAVEN
                    "GRADLE" -> GRADLE
                    "NPM" -> NPM
                    "UNKNOWN" -> UNKNOWN
                    else -> UNKNOWN
                }
            } catch (e: Exception) {
                UNKNOWN
            }
        }
        
        /**
         * 获取依赖类型的名称
         * 避免直接使用kotlin.enums包中的方法
         */
        @JvmStatic
        fun safeName(type: DependencyType): String {
            return when (type) {
                MAVEN -> "MAVEN"
                GRADLE -> "GRADLE"
                NPM -> "NPM"
                UNKNOWN -> "UNKNOWN"
            }
        }
        
        /**
         * 获取依赖类型的序号
         * 避免直接使用kotlin.enums包中的方法
         */
        @JvmStatic
        fun safeOrdinal(type: DependencyType): Int {
            return when (type) {
                MAVEN -> 0
                GRADLE -> 1
                NPM -> 2
                UNKNOWN -> 3
            }
        }
    }
} 