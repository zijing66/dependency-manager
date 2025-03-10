package com.github.zijing66.dependencymanager.models

data class CleanupPreview(
    val path: String,
    val packageName: String,
    val fileSize: Long,
    val lastModified: Long,
    val dependencyType: DependencyType,
    var selected: Boolean = true,
    val relativePath: String
)

data class CleanupSummary(
    val totalFiles: Int,
    val totalSize: Long,
    val previewItems: List<CleanupPreview>
) 