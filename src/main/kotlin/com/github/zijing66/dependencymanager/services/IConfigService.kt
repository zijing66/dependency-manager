package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.CleanupPreview
import com.github.zijing66.dependencymanager.models.CleanupResult
import com.github.zijing66.dependencymanager.models.CleanupSummary
import com.github.zijing66.dependencymanager.models.ConfigOptions
import com.github.zijing66.dependencymanager.models.DependencyType
import com.intellij.openapi.project.Project

interface IConfigService {

    fun getDependencyType(): DependencyType

    fun getLocalRepository(refresh: Boolean): String

    fun updateLocalRepository(newPath: String)

    fun cleanLocalRepository()

    /**
     * 预览清理操作
     * @param configOptions 配置选项，包含includeSnapshot, showInvalidPackages, showPlatformSpecificBinaries等选项
     * @return 清理预览摘要
     */
    fun previewCleanup(
        configOptions: ConfigOptions,
        onProgress: (Int, Int) -> Unit,
        onComplete: (CleanupSummary) -> Unit
    )

    fun cleanupRepository(
        project: Project,
        selectedItems: List<CleanupPreview>,
        onProgress: (Int, Int) -> Unit,
        onComplete: (List<CleanupResult>) -> Unit
    )

}