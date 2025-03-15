package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.ConfigOptions
import com.github.zijing66.dependencymanager.models.DependencyType
import com.github.zijing66.dependencymanager.models.PkgData
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

@Service(Service.Level.PROJECT)
class MavenConfigService(project: Project) : AbstractConfigService(project) {

    private var customRepoPath: String? = null

    private fun getMavenHome(): String {
        // 首先检查环境变量
        System.getenv("MAVEN_HOME")?.let { return it }
        System.getenv("M2_HOME")?.let { return it }

        // 检查默认安装位置
        val defaultPaths = listOf(
            "/usr/local/maven", "/opt/maven", System.getProperty("user.home") + "/.m2"
        )

        for (path in defaultPaths) {
            if (File(path).exists()) return path
        }

        return ""
    }

    override fun getLocalRepository(refresh: Boolean): String {
        customRepoPath?.takeIf { !refresh && isValidRepoPath(it) }?.let { return it }

        val settingsFile = File(getMavenHome(), "conf/settings.xml")
        if (!settingsFile.exists()) {
            return "${System.getProperty("user.home")}/.m2/repository"
        }

        // Parse settings.xml to get local repository path
        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(settingsFile)

        val localRepoNodes = doc.getElementsByTagName("localRepository")
        if (localRepoNodes.length > 0) {
            return localRepoNodes.item(0).textContent
        }

        return "${System.getProperty("user.home")}/.m2/repository"
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
        return file.isFile && (file.name.endsWith(".jar") || file.name.endsWith(".pom") || file.name.endsWith(".jar.lastUpdated") || file.name.endsWith(
            ".pom.lastUpdated"
        ))
    }

    override fun isTargetInvalidFile(file: File): Boolean {
        return file.isFile && (file.name.endsWith(".jar.lastUpdated") || file.name.endsWith(".pom.lastUpdated"))
    }

    override fun getTargetPackageInfo(rootDir: File, file: File): PkgData {
        val versionDir = file.parentFile
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
        return DependencyType.MAVEN
    }

    /**
     * 实现新的 scanRepository 方法，使用 ConfigOptions 对象
     */
    override fun scanRepository(
        dir: File, onDirFound: (File, String, PkgData) -> Unit, configOptions: ConfigOptions
    ) {
        val targetGroupArtifact = configOptions.targetPackage.takeIf { it.isNotEmpty() }?.split(":")
        val pathPkgDataMap = fetchPkgMap(dir)

        pathPkgDataMap.forEach { (path, pkgData) ->
            // 检查是否是SNAPSHOT目录
            val isSnapshotDir = pkgData.packageName.endsWith("SNAPSHOT")

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
                    false
                }
            }

            // 检查是否有lastUpdated文件（失效包）
            val hasLastUpdated = pkgData.invalid

            // 筛选逻辑
            val shouldInclude = when {
                configOptions.showInvalidPackages && hasLastUpdated -> true
                configOptions.targetPackage.isNotEmpty() && isTargetGroupArtifact -> true
                configOptions.includeSnapshot && isSnapshotDir -> true
                else -> false
            }

            // 设置匹配类型
            val matchType = when {
                hasLastUpdated -> "invalid"
                configOptions.targetPackage.isNotEmpty() && isTargetGroupArtifact -> "matched"
                isSnapshotDir -> "snapshot"
                else -> "unknown"
            }

            if (shouldInclude) {
                onDirFound(pkgData.packageDir, matchType, pkgData)
            }
        }
    }

    override fun shouldExcludeDirectory(dir: File): Boolean {
        return false
    }

}