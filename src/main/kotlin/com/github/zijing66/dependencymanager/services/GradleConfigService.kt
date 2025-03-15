package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.DependencyType
import com.github.zijing66.dependencymanager.models.PkgData
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.util.*

@Service(Service.Level.PROJECT)
class GradleConfigService(project: Project) : AbstractConfigService(project) {

    private var customRepoPath: String? = null

    private fun resolveGradleUserHome(): String {
        // 从gradle-wrapper.properties解析GRADLE_USER_HOME
        val wrapperFile = findGradleWrapper()
        var distributionBase = System.getenv("GRADLE_USER_HOME") ?: "${System.getProperty("user.home")}/.gradle"
        if (wrapperFile != null) {
            val properties = Properties().apply { wrapperFile.inputStream().use(::load) }
            val tempDistributionBase = properties.getProperty("distributionBase")
            if (tempDistributionBase != "GRADLE_USER_HOME") {
                distributionBase = tempDistributionBase
            }
        }
        return distributionBase
    }

    private fun findGradleWrapper(): File? {
        // 获取项目的gradle-wrapper.properties文件路径
        val basePath = project.basePath ?: throw RuntimeException("Project base path is null")
        val wrapperFile = File("$basePath/gradle/wrapper/gradle-wrapper.properties")
        return if (wrapperFile.exists()) wrapperFile else null
    }

    override fun getLocalRepository(refresh: Boolean): String {
        customRepoPath?.takeIf { !refresh && isValidRepoPath(it) }?.let { return it }

        // 1. 从项目路径下的 gradle.properties 获取 org.gradle.cache.dir
        val projectPropertiesFile = File("${project.basePath}/gradle.properties")
        if (projectPropertiesFile.exists()) {
            Properties().apply {
                load(projectPropertiesFile.inputStream())
                getProperty("org.gradle.cache.dir")?.takeIf { isValidRepoPath(it) }?.let {
                    return "$it/caches/modules-2/files-2.1".replace('\\', '/')
                }
            }
        }

        // 2. 检查用户目录下的 gradle.properties
        val userPropertiesFile = File("${System.getProperty("user.home")}/.gradle/gradle.properties")
        if (userPropertiesFile.exists()) {
            Properties().apply {
                load(userPropertiesFile.inputStream())
                getProperty("org.gradle.cache.dir")?.takeIf { isValidRepoPath(it) }?.let {
                    return "$it/caches/modules-2/files-2.1".replace('\\', '/')
                }
            }
        }

        // 3. 使用环境变量 GRADLE_USER_HOME
        val gradleUserHome = resolveGradleUserHome()
        return "$gradleUserHome/caches/modules-2/files-2.1".replace('\\', '/')
    }

    // 新增路径更新方法
    override fun updateLocalRepository(newPath: String) {
        if (isValidRepoPath(newPath)) {
            customRepoPath = newPath
        } else {
            throw IllegalArgumentException("Invalid repository path: $newPath")
        }
    }

    override fun isTargetFile(file: File): Boolean {
        return file.isFile && (file.name.endsWith(".jar") || file.name.endsWith(".pom"))
    }

    override fun isTargetInvalidFile(file: File): Boolean {
        return false
    }

    override fun getTargetPackageInfo(rootDir: File, file: File): PkgData {
        val versionDir = file.parentFile.parentFile
        val relativePath = versionDir?.relativeTo(rootDir)?.path?.replace('\\', '/')
        val pathList = relativePath?.split('/')
        return PkgData(
            relativePath = relativePath ?: "",
            packageName = "${
                pathList?.dropLast(2)?.joinToString(".")
            }:${pathList?.get(pathList.size - 2)}:${pathList?.last()}",
            packageDir = versionDir
        )
    }

    override fun getDependencyType(): DependencyType {
        return DependencyType.GRADLE
    }

    override fun scanRepository(
        dir: File, onDirFound: (File, String) -> Unit, includeSnapshot: Boolean, groupArtifact: String?
    ) {
        val targetGroupArtifact = groupArtifact?.split(":")

        if (!includeSnapshot && groupArtifact == null) {
            // 在IDEA中提示用户
            Messages.showErrorDialog(
                project,
                "Please choose Snapshot or input Group/Artifact",
                "Input Error"
            )
            return
        }
        val pathPkgDataMap = fetchPkgMap(dir)

        pathPkgDataMap.forEach({ (path, pkgData) ->
            // 检查是否是SNAPSHOT目录
            val isSnapshotDir = pkgData.packageName.contains("SNAPSHOT")

            // 检查是否匹配指定的group/artifact
            val isTargetGroupArtifact = when (targetGroupArtifact?.size) {
                2 -> {
                    val (group, artifact) = targetGroupArtifact
                    pkgData.packageName.startsWith("${group}:${artifact}")
                }

                1 -> {
                    val group = targetGroupArtifact[0]
                    pkgData.packageName.startsWith(group)
                }

                else -> {
                    true
                }
            }
            // 筛选逻辑
            val shouldInclude = when {
                // 如果是SNAPSHOT但目录不包含SNAPSHOT，则排除
                includeSnapshot && !isSnapshotDir -> false
                // 如果指定了groupId:artifactId但不匹配，则排除
                groupArtifact != null && !isTargetGroupArtifact -> false
                // 其他情况都包含
                else -> true
            }
            // 设置匹配类型
            val matchType = when {
                groupArtifact != null && isTargetGroupArtifact -> "matched"
                includeSnapshot && isSnapshotDir -> "snapshot"
                else -> "invalid"
            }

            if (shouldInclude) {
                onDirFound(pkgData.packageDir, matchType)
            }
        })
    }

}