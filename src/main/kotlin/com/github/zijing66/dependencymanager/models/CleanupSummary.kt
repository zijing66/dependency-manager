package com.github.zijing66.dependencymanager.models

data class CleanupSummary(
    val totalScannedCount: Int,
    val totalCount: Int,
    val totalSize: Long,
    val previewItems: List<CleanupPreview>
) 