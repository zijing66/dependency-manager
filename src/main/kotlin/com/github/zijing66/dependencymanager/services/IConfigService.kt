package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.CleanupPreview
import com.github.zijing66.dependencymanager.models.CleanupResult
import com.github.zijing66.dependencymanager.models.CleanupSummary
import com.intellij.openapi.project.Project

interface IConfigService {

    fun getLocalRepository(fresh: Boolean): String

    fun updateLocalRepository(newPath: String)

    fun previewCleanup(
        includeSnapshot: Boolean = false,
        groupArtifact: String? = null
    ): CleanupSummary

    fun cleanupRepository(
        project: Project,
        selectedItems: List<CleanupPreview>,
        onProgress: (Int, Int) -> Unit,
        onComplete: (List<CleanupResult>) -> Unit
    )

}