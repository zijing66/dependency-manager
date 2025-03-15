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
    PIP,
    UNKNOWN;
} 