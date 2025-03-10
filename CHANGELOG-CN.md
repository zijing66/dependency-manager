# 依赖管理插件 更新日志

[English Change Log](CHANGELOG.md)

## [1.0.1]

### 新增

- 支持Gradle包管理
- 支持切换maven和gradle
- 优化界面样式

### 修复

- 修复若干问题

## [1.0.0]

### 新增

- 支持删除Maven下载失败的依赖
- 支持删除SNAPSHOT包和指定groupId/artifactId的包
- 兼容性优化和搜索性能优化

### 修复

- 修复与IntelliJ IDEA 2021.3版本的兼容性问题
- 修复ContentFactory.getInstance()方法未找到错误
- 修复kotlin.enums包未找到错误
- 修复DependencyType枚举中的JVM签名冲突

## [未发布]

### 新增

- 自动分析项目依赖类型（Maven/Gradle）
- Maven本地仓库无效依赖清理功能
- 可视化预览界面支持文件多选操作
- 带进度监控的多线程清理机制
