package com.github.zijing66.dependencymanager.models

data class CleanupSummary(
    val totalFiles: Int,
    val totalSize: Long,
    val previewItems: List<CleanupPreview>
) 