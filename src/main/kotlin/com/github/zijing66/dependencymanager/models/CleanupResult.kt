package com.github.zijing66.dependencymanager.models

data class CleanupResult(
    val path: String,
    val packageName: String,
    val dependencyType: DependencyType,
    val success: Boolean,
    val errorMessage: String? = null
) 