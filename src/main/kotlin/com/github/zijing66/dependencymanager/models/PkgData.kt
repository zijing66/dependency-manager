package com.github.zijing66.dependencymanager.models

import java.io.File

data class PkgData(
    val relativePath: String,
    val packageName: String,
    val packageDir: File,
    var invalid: Boolean = false
) 