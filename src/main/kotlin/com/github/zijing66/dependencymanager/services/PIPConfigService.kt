package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.ConfigOptions
import com.github.zijing66.dependencymanager.models.DependencyType
import com.github.zijing66.dependencymanager.models.PkgData
import com.github.zijing66.dependencymanager.models.PythonEnvironmentType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.io.IOException
import java.util.*

@Service(Service.Level.PROJECT)
class PIPConfigService(project: Project) : AbstractConfigService(project) {

    private var customRepoPath: String? = null
    private var environmentType: PythonEnvironmentType = PythonEnvironmentType.SYSTEM
    private var environmentPath: String? = null
    private var condaInstallPath: String? = null

    // Python环境中常见的需要排除的目录
    private val commonPythonExclusions = listOf(
        "__pycache__",   // Python缓存目录
        ".pytest_cache",  // Pytest缓存
        "dist",          // 分发目录
        "docs",          // 文档
        "tests",         // 测试目录
        "test"           // 测试目录
    )
    
    // 正则表达式成员变量
    private val hashDirectoryRegex = Regex("[a-f0-9]{30,}")
    private val versionNumberRegex = Regex("\\d+(?:\\.\\d+)*.*")
    private val snapshotVersionRegex = Regex("\\d+\\.\\d+\\.\\d+(-dev\\d*|-a\\d+|-alpha\\d*|-b\\d+|-beta\\d*|-rc\\d+|-pre\\d*|\\.dev\\d*|\\.post\\d*)")
    private val nativeBinaryRegex = Regex(".*\\.(cp\\d+|py\\d+)-(win|linux|darwin|macosx|manylinux)_(x86_64|amd64|arm64|i686).*")
    private val wheelPatternRegex = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)-(.+?)\\.whl$")
    private val sdistPatternRegex = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)")
    private val setupPatternRegex = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)")
    private val eggPatternRegex = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)-py\\d\\.\\d+\\.egg$")
    private val eggInfoPatternRegex = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)\\.egg-info$")
    private val distInfoPatternRegex = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)\\.dist-info$")
    private val nameRegex = Regex("Name:\\s*([^\r\n]+)")
    private val versionRegex = Regex("Version:\\s*([^\r\n]+)")
    private val setupNameRegex = Regex("name\\s*=\\s*['\"]([^'\"]+)['\"]")
    private val setupVersionRegex = Regex("version\\s*=\\s*['\"]([^'\"]+)['\"]")
    private val cacheDirRegex = Regex("cache-dir\\s*=\\s*(.+)")
    private val tarArchiveRegex = Regex("\\.tar\\.gz$|\\.tar\\.bz2$|\\.tar\\.xz$|\\.zip$")
    private val pythonPackageNormalizeRegex = Regex("[_.-]+")
    private val condaNameRegex = Regex("name:\\s*([^\\s]+)")
    private val condaEnvDirsRegex = Regex("\"envs_dirs\"\\s*:\\s*\\[([^]]+)]")

    /**
     * 设置Python环境类型并清除自定义路径
     * 当UI中选择不同的环境类型时调用此方法
     * @param type 环境类型
     */
    fun setEnvironmentType(type: PythonEnvironmentType) {
        // 如果环境类型没有变化，不需要刷新
        if (environmentType == type && environmentPath != null) {
            return
        }
        
        environmentType = type
        // 清除环境路径和自定义路径，强制重新检测
        environmentPath = null
        customRepoPath = null
        
        // 根据环境类型选择性地刷新对应环境数据
        when (type) {
            PythonEnvironmentType.VENV -> {
                environmentPath = findVenvPath()?.let { findSitePackagesInVenv(it) }
            }
            PythonEnvironmentType.CONDA -> {
                // 如果已经有condaInstallPath但环境路径为空，尝试重新查找
                if (condaInstallPath != null) {
                    val sitePkgsPath = findSitePackagesInConda(condaInstallPath!!)
                    if (sitePkgsPath != null) {
                        environmentPath = sitePkgsPath
                    }
                } else {
                    // 尝试检测conda安装
                    detectCondaInstallation()
                }
            }
            PythonEnvironmentType.PIPENV -> {
                val projectPath = project.basePath
                if (projectPath != null) {
                    val pipenvPath = getPipenvVirtualEnvPath(projectPath)
                    if (pipenvPath != null) {
                        environmentPath = findSitePackagesInVenv(pipenvPath)
                    }
                }
            }
            PythonEnvironmentType.SYSTEM -> {
                // 查找系统Python的site-packages
                environmentPath = findSystemPythonSitePackages()
            }
        }
    }

    /**
     * 判断Python相关的目录是否应该被排除
     * @param dir 要检查的目录
     * @return 如果目录应该被排除则返回true
     */
    override fun shouldExcludeDirectory(dir: File): Boolean {
        // 如果是site-packages目录下的文件，特殊处理
        if (dir.path.contains("site-packages")) {
            // 直接在site-packages下
            val parentName = dir.parentFile?.name
            if (parentName == "site-packages") {
                // 仅排除常见的非包目录
                return dir.name == "__pycache__" || 
                       dir.name == "tests" || 
                       dir.name == "test" || 
                       dir.name == ".pytest_cache" ||
                       dir.name.startsWith("pip-") || // pip元数据目录
                       dir.name.startsWith("setuptools-") // setuptools元数据目录
            }
            // 针对dist-info和egg-info目录不再深入遍历，一次性获取元数据
            else if (dir.parentFile?.name?.endsWith(".dist-info") == true || 
                     dir.parentFile?.name?.endsWith(".egg-info") == true) {
                return true
            }
            
            // 处理包内部目录，排除测试和缓存目录
            return dir.name == "__pycache__" || 
                   dir.name == "tests" || 
                   dir.name == "test" || 
                   dir.name == ".pytest_cache"
        }
        
        // 排除以点开头的目录（如.venv等）
        if (dir.name.startsWith(".")) {
            return true
        }
        
        // 排除commonPythonExclusions中的目录
        if (dir.name in commonPythonExclusions) {
            return true
        }
        
        // 特别处理Python虚拟环境中的特殊情况
        when (environmentType) {
            PythonEnvironmentType.VENV, PythonEnvironmentType.PIPENV -> {
                // 虚拟环境中的lib/python*/site-packages/pkg_resources目录可能包含大量符号链接
                if (dir.path.contains("site-packages") && 
                    (dir.name == "pkg_resources" || dir.name == ".nspkg-patches")) {
                    return true
                }
            }
            PythonEnvironmentType.CONDA -> {
                // Conda环境中的pkgs目录包含缓存的包，不需要遍历
                if (dir.name == "pkgs" && dir.path.contains("conda") && dir.path.contains("envs")) {
                    return true
                }
                // conda-meta目录包含元数据，不需要遍历
                if (dir.name == "conda-meta") {
                    return true
                }
            }
            else -> {
                // 处理系统Python中的情况
            }
        }
        
        // 处理pip缓存目录，限制遍历只到获取包名版本号的层级
        if (dir.path.contains("pip/Cache") || 
            dir.path.contains("pip/cache") || 
            dir.path.contains("pip\\Cache")) {
            
            // pip缓存目录通常是按哈希值组织的
            if (dir.name.length == 2 && dir.isDirectory) {
                // 这是缓存根目录的第一级哈希目录（两个字符），保留
                return false
            } else if (dir.parentFile?.name?.length == 2 && dir.name.matches(hashDirectoryRegex)) {
                // 这是第二级哈希目录，包含了实际的包文件，保留
                return false
            } else if (dir.path.contains("http") || dir.path.contains("https")) {
                // 这是源缓存目录，不需要深入遍历
                return dir.listFiles()?.none { it.isFile && (it.name.endsWith(".whl") || it.name.endsWith(".tar.gz")) } ?: true
            }
        }
        
        // 排除一些特殊工具创建的缓存目录
        if (dir.name.endsWith(".dist-info") && dir.path.contains("site-packages")) {
            try {
                val installerFile = File(dir, "INSTALLER")
                if (installerFile.exists() && installerFile.readText().contains("pip")) {
                    return true
                }
            } catch (e: Exception) {
                // 如果读取失败，不排除该目录
                return false
            }
        }
        
        return false
    }


    /**
     * 检测conda安装位置
     */
    private fun detectCondaInstallation() {
        val userHome = System.getProperty("user.home")
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        
        // Conda/Miniconda可能的安装位置
        val possibleCondaPaths = when {
            osName.contains("win") -> listOf(
                "C:/ProgramData/Anaconda3",
                "C:/ProgramData/Miniconda3",
                "$userHome/Anaconda3",
                "$userHome/Miniconda3"
            )
            osName.contains("mac") -> listOf(
                "/opt/anaconda3",
                "/opt/miniconda3",
                "$userHome/anaconda3",
                "$userHome/miniconda3"
            )
            else -> listOf( // Linux/Unix
                "/opt/anaconda3",
                "/opt/miniconda3",
                "$userHome/anaconda3",
                "$userHome/miniconda3"
            )
        }
        
        // 尝试找到Conda安装位置
        for (condaPath in possibleCondaPaths) {
            if (File(condaPath).exists() && isValidCondaDir(condaPath)) {
                condaInstallPath = condaPath
                
                // 检查site-packages目录
                val sitePkgsPath = findSitePackagesInConda(condaPath)
                if (sitePkgsPath != null) {
                    environmentPath = sitePkgsPath
                    break
                }
            }
        }
    }

    /**
     * 检查是否是有效的Conda目录
     */
    private fun isValidCondaDir(condaPath: String): Boolean {
        return File(condaPath).isDirectory && (
            File("$condaPath/bin/conda").exists() ||
            File("$condaPath/Scripts/conda.exe").exists() ||
            File("$condaPath/condabin/conda").exists() ||
            File("$condaPath/condabin/conda.exe").exists() ||
            File("$condaPath/envs").exists()
        )
    }

    /**
     * 在Conda目录中查找site-packages
     */
    private fun findSitePackagesInConda(condaPath: String): String? {
        // 检查site-packages目录的常见位置
        val sitePkgsPathWin = "$condaPath/Lib/site-packages"
        if (File(sitePkgsPathWin).exists()) {
            return sitePkgsPathWin
        }
        
        // 对于Unix系统，尝试找到python3.*目录
        val libDir = File("$condaPath/lib")
        if (libDir.exists() && libDir.isDirectory) {
            val pythonDirs = libDir.listFiles { file -> 
                file.isDirectory && file.name.startsWith("python3")
            }
            
            if (pythonDirs?.isNotEmpty() == true) {
                val sitePkgs = "${pythonDirs[0].absolutePath}/site-packages"
                if (File(sitePkgs).exists()) {
                    return sitePkgs
                }
            }
        }
        
        return null
    }

    private fun getPipenvVirtualEnvPath(projectPath: String): String? {
        // 尝试运行pipenv --venv命令获取路径
        try {
            val process = ProcessBuilder("pipenv", "--venv")
                .directory(File(projectPath))
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && output.isNotEmpty()) {
                return output
            }
        } catch (e: Exception) {
            // 命令执行失败，忽略异常
        }
        
        // 尝试检查~/.local/share/virtualenvs目录
        val userHome = System.getProperty("user.home")
        val pipenvRoot = File("$userHome/.local/share/virtualenvs")
        if (pipenvRoot.exists() && pipenvRoot.isDirectory) {
            // 查找与项目名称相关的虚拟环境
            val projectName = File(projectPath).name
            val possibleEnvs = pipenvRoot.listFiles { file -> 
                file.isDirectory && file.name.startsWith(projectName) 
            }
            
            if (possibleEnvs?.isNotEmpty() == true) {
                return possibleEnvs[0].absolutePath
            }
        }
        
        return null
    }

    private fun getCondaEnvironmentName(projectPath: String): String? {
        // 从conda环境文件中读取环境名称
        val envFile = when {
            File("$projectPath/environment.yml").exists() -> File("$projectPath/environment.yml")
            File("$projectPath/conda-env.yml").exists() -> File("$projectPath/conda-env.yml")
            else -> return null
        }
        
        val content = envFile.readText()
        val nameMatch = condaNameRegex.find(content)
        return nameMatch?.groupValues?.get(1)
    }

    private fun getCondaEnvironmentPath(envName: String): String? {
        // 查找conda命令的位置
        val condaCmd = findCondaCommand()
        
        if (condaCmd != null) {
            try {
                val process = ProcessBuilder(condaCmd, "info", "--json")
                    .redirectErrorStream(true)
                    .start()
                
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                
                if (exitCode == 0 && output.isNotEmpty()) {
                    // 使用简单正则表达式解析JSON输出
                    val envDirsMatch = condaEnvDirsRegex.find(output)
                    val envDirsStr = envDirsMatch?.groupValues?.get(1) ?: return null
                    
                    // 解析环境目录列表
                    val envDirs = envDirsStr.split(",")
                        .map { it.trim().trim('"') }
                        .filter { it.isNotEmpty() }
                    
                    // 在每个环境目录中查找指定的环境
                    for (envDir in envDirs) {
                        val fullEnvPath = File("$envDir/$envName")
                        if (fullEnvPath.exists() && fullEnvPath.isDirectory) {
                            return fullEnvPath.absolutePath
                        }
                    }
                }
            } catch (e: IOException) {
                Messages.showErrorDialog(
                    project,
                    "Error: Failed to execute Conda command. Error: ${e.message}",
                    "Command Error"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            Messages.showErrorDialog(
                project,
                "Error: Conda command not found. Please ensure Conda is installed and set the correct Conda installation directory.",
                "Command Error"
            )
        }
        
        // 如果无法通过conda命令获取，使用默认路径
        val userHome = System.getProperty("user.home")
        val defaultCondaPaths = listOf(
            "$userHome/anaconda3/envs/$envName",
            "$userHome/miniconda3/envs/$envName",
            "C:/ProgramData/Anaconda3/envs/$envName",
            "C:/ProgramData/Miniconda3/envs/$envName",
            "/opt/anaconda3/envs/$envName",
            "/opt/miniconda3/envs/$envName"
        )
        
        for (path in defaultCondaPaths) {
            if (File(path).exists()) {
                return path
            }
        }
        
        return null
    }

    /**
     * 根据当前环境设置查找conda命令的位置
     * @return conda命令的完整路径，如果找不到则返回null
     */
    private fun findCondaCommand(): String? {
        // 首先检查condaInstallPath是否已经设置
        if (condaInstallPath != null) {
            val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
            val condaExecutable = if (osName.contains("win")) {
                // 检查常见的conda可执行文件位置
                listOf(
                    "$condaInstallPath/Scripts/conda.exe", 
                    "$condaInstallPath/condabin/conda.bat", 
                    "$condaInstallPath/condabin/conda.exe"
                ).find { File(it).exists() }
            } else {
                // Unix系统中的conda位置
                listOf(
                    "$condaInstallPath/bin/conda",
                    "$condaInstallPath/condabin/conda"
                ).find { File(it).exists() }
            }
            
            if (condaExecutable != null) {
                return condaExecutable
            }
        }
        
        // 如果condaInstallPath未设置或没找到conda，尝试检测系统路径
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        val userHome = System.getProperty("user.home")
        
        // 可能的conda安装位置
        val possibleCondaPaths = if (osName.contains("win")) {
            listOf(
                "C:/ProgramData/Anaconda3",
                "C:/ProgramData/Miniconda3",
                "$userHome/Anaconda3",
                "$userHome/Miniconda3"
            )
        } else if (osName.contains("mac")) {
            listOf(
                "/opt/anaconda3",
                "/opt/miniconda3",
                "$userHome/anaconda3",
                "$userHome/miniconda3"
            )
        } else {
            listOf(
                "/opt/anaconda3",
                "/opt/miniconda3",
                "$userHome/anaconda3",
                "$userHome/miniconda3"
            )
        }
        
        // 检查每个可能的路径下的conda可执行文件
        for (basePath in possibleCondaPaths) {
            val condaExecutable = if (osName.contains("win")) {
                listOf(
                    "$basePath/Scripts/conda.exe", 
                    "$basePath/condabin/conda.bat", 
                    "$basePath/condabin/conda.exe"
                ).find { File(it).exists() }
            } else {
                listOf(
                    "$basePath/bin/conda",
                    "$basePath/condabin/conda"
                ).find { File(it).exists() }
            }
            
            if (condaExecutable != null) {
                // 记录找到的conda安装路径
                condaInstallPath = basePath
                return condaExecutable
            }
        }
        
        // 如果还是找不到，尝试使用PATH中的conda命令
        try {
            val process = if (osName.contains("win")) {
                ProcessBuilder("where", "conda").start()
            } else {
                ProcessBuilder("which", "conda").start()
            }
            
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && output.isNotEmpty()) {
                val condaPath = output.lines().firstOrNull()
                if (condaPath != null && File(condaPath).exists()) {
                    // 设置conda安装目录
                    val condaFile = File(condaPath)
                    condaInstallPath = condaFile.parentFile?.parentFile?.absolutePath
                    return condaPath
                }
            }
        } catch (e: Exception) {
            // 忽略异常，表示找不到conda命令
        }
        
        return null
    }

    private fun getPipCacheDirectory(): String {
        // 获取pip标准缓存目录（非虚拟环境）
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        val userHome = System.getProperty("user.home")
        
        return when {
            osName.contains("win") -> {
                // Windows: pip缓存通常在 %LOCALAPPDATA%\pip\Cache
                val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
                "$localAppData/pip/Cache"
            }
            osName.contains("mac") -> {
                // macOS: pip缓存通常在 ~/Library/Caches/pip
                "$userHome/Library/Caches/pip"
            }
            else -> {
                // Linux/Unix: pip缓存通常在 ~/.cache/pip
                "$userHome/.cache/pip"
            }
        }
    }

    private fun getVenvCacheDirectory(venvPath: String): String {
        // 虚拟环境中的pip缓存
        return "$venvPath/pip-cache"
    }

    private fun getCondaCacheDirectory(condaPath: String): String {
        // Conda环境中的pip缓存
        return "$condaPath/pip-cache"
    }

    private fun getPipenvCacheDirectory(pipenvPath: String): String {
        // Pipenv环境中的pip缓存
        return "$pipenvPath/.cache/pip"
    }

    override fun getLocalRepository(refresh: Boolean): String {
        // 如果有自定义路径且不需要刷新，直接返回
        customRepoPath?.takeIf { !refresh && isValidRepoPath(it) }?.let { return it }

        // 如果是刷新或者第一次调用，且不是由setEnvironmentType调用时
        if ((refresh || environmentPath == null) && 
            (refresh || Thread.currentThread().stackTrace.none { it.methodName == "setEnvironmentType" })) {
            // 如果需要刷新，先检测环境
            if (refresh) {
                detectPythonEnvironment()
            }
            
            // 根据环境类型选择性地刷新对应环境数据
            when (environmentType) {
                PythonEnvironmentType.VENV -> {
                    environmentPath = findVenvPath()?.let { findSitePackagesInVenv(it) }
                }
                PythonEnvironmentType.CONDA -> {
                    // 如果已经有condaInstallPath但没有环境路径，尝试重新查找
                    if (condaInstallPath != null) {
                        val sitePkgsPath = findSitePackagesInConda(condaInstallPath!!)
                        if (sitePkgsPath != null) {
                            environmentPath = sitePkgsPath
                        }
                    } else {
                        // 尝试检测conda安装
                        detectCondaInstallation()
                    }
                }
                PythonEnvironmentType.PIPENV -> {
                    val projectPath = project.basePath
                    if (projectPath != null) {
                        val pipenvPath = getPipenvVirtualEnvPath(projectPath)
                        if (pipenvPath != null) {
                            environmentPath = findSitePackagesInVenv(pipenvPath)
                        }
                    }
                }
                PythonEnvironmentType.SYSTEM -> {
                    // 查找系统Python的site-packages
                    environmentPath = findSystemPythonSitePackages()
                }
            }
        }

        // 如果找到了site-packages路径，返回它
        if (environmentPath != null && File(environmentPath!!).exists() && isValidRepoPath(environmentPath!!)) {
            return environmentPath!!
        }

        // 找不到site-packages目录，返回pip缓存目录
        val cacheDir = when (environmentType) {
            PythonEnvironmentType.VENV -> {
                val venvPath = findVenvPath()
                if (venvPath != null) getVenvCacheDirectory(venvPath) else getPipCacheDirectory()
            }
            PythonEnvironmentType.CONDA -> {
                if (condaInstallPath != null) getCondaCacheDirectory(condaInstallPath!!) else getPipCacheDirectory()
            }
            PythonEnvironmentType.PIPENV -> {
                val projectPath = project.basePath
                val pipenvPath = if (projectPath != null) getPipenvVirtualEnvPath(projectPath) else null
                if (pipenvPath != null) getPipenvCacheDirectory(pipenvPath) else getPipCacheDirectory()
            }
            else -> {
                // 检查pip.conf或pip.ini中的配置
                extractPipConfigCacheDir() ?: getPipCacheDirectory()
            }
        }
        
        return cacheDir.replace("\\", "/")
    }
    
    /**
     * 查找virtualenv路径下特定的site-packages目录
     */
    private fun findVenvPath(): String? {
        val projectPath = project.basePath ?: return null
        
        // 检查项目中常见的虚拟环境目录
        val venvDirs = listOf(
            "$projectPath/venv",
            "$projectPath/.venv",
            "$projectPath/env"
        )
        
        for (venvDir in venvDirs) {
            if (File(venvDir).exists()) {
                return venvDir
            }
        }
        
        return null
    }

    private fun extractPipConfigCacheDir(): String? {
        // 检查pip.conf或pip.ini中的配置
        val pipConfigFile = when {
            System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win") -> 
                File("${System.getenv("APPDATA") ?: "${System.getProperty("user.home")}/AppData/Roaming"}/pip/pip.ini")
            else -> 
                File("${System.getProperty("user.home")}/.config/pip/pip.conf")
        }

        if (pipConfigFile.exists()) {
            val cacheDir = extractCacheDirFromPipConfig(pipConfigFile)
            if (cacheDir != null && isValidRepoPath(cacheDir)) {
                return cacheDir
            }
        }
        
        return null
    }

    private fun extractCacheDirFromPipConfig(pipConfigFile: File): String? {
        val content = pipConfigFile.readText()
        val cacheDirMatch = cacheDirRegex.find(content)
        return cacheDirMatch?.groupValues?.get(1)?.trim()
    }

    override fun updateLocalRepository(newPath: String) {
        if (isValidRepoPath(newPath)) {
            customRepoPath = newPath
        } else {
            throw IllegalArgumentException("Invalid repository path: $newPath")
        }
    }

    override fun isTargetFile(file: File): Boolean {
        // 检查是否是pip包文件
        return when {
            // 标准分发格式
            file.isFile && (
                file.name.endsWith(".whl") ||       // Wheel 格式
                file.name.endsWith(".tar.gz") ||    // 源码分发包 (sdist)
                file.name.endsWith(".zip") ||       // Zip 格式分发包
                file.name.endsWith(".egg") ||       // Egg 二进制分发包
                file.name.endsWith(".tar.bz2") ||   // BZ2 压缩的源码包
                file.name.endsWith(".tar.xz")       // XZ 压缩的源码包
            ) -> true
            
            // 包元数据目录及文件
            file.name.endsWith(".egg-info") -> true        // 旧式 Egg 元数据
            file.name.endsWith(".dist-info") -> true       // 新式 Dist-info 元数据目录
            
            // 重要的元数据文件
            (file.name == "RECORD" || file.name == "METADATA" || file.name == "WHEEL") 
                && file.path.contains("dist-info") -> true  // Dist-info 中的关键文件
            
            file.name == "PKG-INFO" && (
                file.path.contains("egg-info") || 
                file.path.contains(".dist-info") || 
                file.parentFile?.name == "EGG-INFO"
            ) -> true  // 包信息文件
            
            // 安装目录中的关键文件
            file.name == "setup.py" && 
                (file.parentFile?.name?.contains("-") == true) -> true  // 源码包中的安装文件
            
            file.name == "direct_url.json" && 
                file.path.contains(".dist-info") -> true   // PEP 610 直接引用信息
                
            file.name == "installed-files.txt" && 
                file.path.contains(".egg-info") -> true    // 已安装文件列表
                
            file.name == "requires.txt" && 
                (file.path.contains(".egg-info") || file.path.contains(".dist-info")) -> true  // 依赖要求

            // 其他情况
            else -> false
        }
    }

    override fun isTargetInvalidFile(file: File): Boolean {
        // 检查是否有失效的包文件
        return file.isFile && (
            file.name.endsWith(".incomplete") || 
            file.name.endsWith(".whl.part") ||
            file.name.endsWith(".tar.gz.part")
        )
    }

    override fun getTargetPackageInfo(rootDir: File, file: File): PkgData {
        val packageDir = if (file.name == "packages.json") file.parentFile else file.parentFile
        val relativePath = packageDir?.relativeTo(rootDir)?.path?.replace('\\', '/')
        
        // 根据不同文件类型进行处理
        var packageName = "unknown"
        var version = "unknown"
        
        when {
            file.name.endsWith(".whl") -> {
                // 从wheel文件名解析包名和版本
                // 格式通常是: {package_name}-{version}-{python_tag}-{abi_tag}-{platform_tag}.whl
                // 示例: numpy-1.24.3-cp39-cp39-win_amd64.whl
                val wheelFilename = file.name
                val parts = wheelFilename.split("-")
                if (wheelFilename.endsWith(".whl") && parts.size >= 3) {
                    val match = wheelPatternRegex.find(wheelFilename)
                    
                    if (match != null) {
                        packageName = match.groupValues[1]
                        version = if (parts[1].matches(versionNumberRegex)) {
                            parts[1]
                        } else {
                            "unknown"
                        }
                    } else {
                        // 兼容性处理：尝试基础拆分
                        val nameParts = file.nameWithoutExtension.split("-")
                        if (nameParts.size >= 2) {
                            packageName = nameParts[0]
                            // 检查第二部分是否像个版本号
                            version = if (nameParts[1].matches(versionNumberRegex)) {
                                nameParts[1]
                            } else {
                                "unknown"
                            }
                        } else {
                            packageName = file.nameWithoutExtension
                            version = "unknown"
                        }
                    }
                } else {
                    packageName = file.nameWithoutExtension
                    version = "unknown"
                }
            }
            // 源码分发包和其他压缩包格式
            file.name.endsWith(".tar.gz") || file.name.endsWith(".zip") || 
            file.name.endsWith(".tar.bz2") || file.name.endsWith(".tar.xz") -> {
                // 从分发包文件名解析包名和版本
                // 格式通常是: {package_name}-{version}.扩展名
                // 示例: requests-2.28.1.tar.gz, django-4.2.0.zip
                
                // 获取基本文件名（去掉所有压缩扩展名）
                val baseName = file.name
                    .replace(tarArchiveRegex, "")
                
                val sdistMatch = sdistPatternRegex.find(baseName)
                
                if (sdistMatch != null) {
                    packageName = sdistMatch.groupValues[1]
                    version = sdistMatch.groupValues[2]
                } else {
                    // 兼容模式：尝试更简单的拆分
                    val nameParts = baseName.split("-")
                    if (nameParts.size >= 2 && nameParts.last().matches(versionNumberRegex)) {
                        packageName = nameParts.dropLast(1).joinToString("-")
                        version = nameParts.last()
                    } else {
                        packageName = baseName
                        version = "unknown"
                    }
                }
            }
            // .egg 文件处理
            file.name.endsWith(".egg") -> {
                // .egg 文件格式: {package_name}-{version}-{python_tag}.egg
                // 示例: SQLAlchemy-1.4.46-py3.9.egg
                val eggFilename = file.name
                val parts = eggFilename.split("-")
                if (eggFilename.endsWith(".egg") && parts.size >= 3) {
                    val eggMatch = eggPatternRegex.find(eggFilename)
                    
                    if (eggMatch != null) {
                        packageName = eggMatch.groupValues[1]
                        version = if (parts[1].matches(versionNumberRegex)) {
                            parts[1]
                        } else {
                            "unknown"
                        }
                    } else {
                        // 尝试更简单的匹配模式
                        val simpleParts = file.nameWithoutExtension.split("-")
                        if (simpleParts.size >= 2) {
                            // 检查倒数第二部分是否是版本号
                            val possibleVersionIndex = if (simpleParts.last().startsWith("py")) 
                                simpleParts.size - 2 else simpleParts.size - 1
                            
                            if (possibleVersionIndex >= 1 && 
                                simpleParts[possibleVersionIndex].matches(versionNumberRegex)) {
                                packageName = simpleParts.subList(0, possibleVersionIndex).joinToString("-")
                                version = simpleParts[possibleVersionIndex]
                            } else {
                                packageName = file.nameWithoutExtension
                                version = "unknown"
                            }
                        } else {
                            packageName = file.nameWithoutExtension
                            version = "unknown"
                        }
                    }
                } else {
                    packageName = file.nameWithoutExtension
                    version = "unknown"
                }
            }
            // egg-info 目录或文件
            file.name.endsWith(".egg-info") -> {
                // 解析egg-info目录名
                // 格式通常是: {package_name}-{version}.egg-info
                // 示例: SQLAlchemy-1.4.46.egg-info
                val eggInfoFilename = file.name
                val parts = eggInfoFilename.split("-")
                if (eggInfoFilename.endsWith(".egg-info") && parts.size >= 2) {
                    val eggInfoMatch = eggInfoPatternRegex.find(eggInfoFilename)
                    
                    if (eggInfoMatch != null) {
                        packageName = eggInfoMatch.groupValues[1]
                        version = if (parts[1].matches(versionNumberRegex)) {
                            parts[1]
                        } else {
                            "unknown"
                        }
                    } else {
                        // 检查是否有PKG-INFO文件，可以从中读取更准确的信息
                        val pkgInfoFile = File(packageDir, "PKG-INFO") 
                        if (pkgInfoFile.exists()) {
                            try {
                                val pkgInfoContent = pkgInfoFile.readText()
                                val nameMatch = nameRegex.find(pkgInfoContent)
                                val versionMatch = versionRegex.find(pkgInfoContent)
                                
                                if (nameMatch != null) {
                                    packageName = nameMatch.groupValues[1].trim()
                                } else {
                                    packageName = file.name.removeSuffix(".egg-info")
                                }
                                
                                if (versionMatch != null) {
                                    version = versionMatch.groupValues[1].trim()
                                } else {
                                    version = "unknown"
                                }
                            } catch (e: Exception) {
                                // 如果读取失败，继续使用文件名解析
                                // 兼容模式：尝试简单拆分
                                val nameParts = file.name.removeSuffix(".egg-info").split("-")
                                if (nameParts.size >= 2 && nameParts.last().matches(versionNumberRegex)) {
                                    packageName = nameParts.dropLast(1).joinToString("-")
                                    version = nameParts.last()
                                } else {
                                    packageName = file.name.removeSuffix(".egg-info")
                                    version = "unknown"
                                }
                            }
                        } else {
                            // 兼容模式：尝试简单拆分
                            val nameParts = file.name.removeSuffix(".egg-info").split("-")
                            if (nameParts.size >= 2 && nameParts.last().matches(versionNumberRegex)) {
                                packageName = nameParts.dropLast(1).joinToString("-")
                                version = nameParts.last()
                            } else {
                                packageName = file.name.removeSuffix(".egg-info")
                                version = "unknown"
                            }
                        }
                    }
                }
            }
            // dist-info 目录处理
            file.name.endsWith(".dist-info") || (file.parentFile != null && file.parentFile.name.endsWith(".dist-info")) -> {
                // 从dist-info目录名解析包信息
                // 格式通常是: {package_name}-{version}.dist-info
                // 示例: flask-2.2.3.dist-info
                val dirName = if (file.name.endsWith(".dist-info")) file.name else file.parentFile.name
                val distInfoFilename = if (file.name.endsWith(".dist-info")) file.name else file.parentFile.name
                val distInfoMatch = distInfoPatternRegex.find(distInfoFilename)
                
                if (distInfoMatch != null) {
                    packageName = distInfoMatch.groupValues[1]
                    version = distInfoMatch.groupValues[2]
                } else {
                    // 尝试从METADATA文件获取信息
                    val metadataFile = if (file.name.endsWith(".dist-info")) {
                        File(file, "METADATA")
                    } else { 
                        File(file.parentFile, "METADATA")
                    }
                    
                    if (metadataFile.exists()) {
                        try {
                            val metadataContent = metadataFile.readText()
                            val nameMatch = nameRegex.find(metadataContent)
                            val versionMatch = versionRegex.find(metadataContent)
                            
                            if (nameMatch != null) {
                                packageName = nameMatch.groupValues[1].trim()
                            } else {
                                packageName = dirName.removeSuffix(".dist-info")
                            }
                            
                            if (versionMatch != null) {
                                version = versionMatch.groupValues[1].trim()
                            } else {
                                version = "unknown"
                            }
                        } catch (e: Exception) {
                            // 如果读取失败，继续使用目录名解析
                            // 兼容模式：尝试简单拆分
                            val nameParts = dirName.removeSuffix(".dist-info").split("-")
                            if (nameParts.size >= 2 && nameParts.last().matches(versionNumberRegex)) {
                                packageName = nameParts.dropLast(1).joinToString("-")
                                version = nameParts.last()
                            } else {
                                packageName = dirName.removeSuffix(".dist-info")
                                version = "unknown"
                            }
                        }
                    } else {
                        // 兼容模式：尝试简单拆分
                        val nameParts = dirName.removeSuffix(".dist-info").split("-")
                        if (nameParts.size >= 2 && nameParts.last().matches(versionNumberRegex)) {
                            packageName = nameParts.dropLast(1).joinToString("-")
                            version = nameParts.last()
                        } else {
                            packageName = dirName.removeSuffix(".dist-info")
                            version = "unknown"
                        }
                    }
                }
            }
            // dist-info 目录中的元数据文件
            file.name == "RECORD" || file.name == "METADATA" || file.name == "WHEEL" -> {
                if (file.parentFile != null && file.parentFile.name.endsWith(".dist-info")) {
                    // 使用父目录解析
                    val dirName = file.parentFile.name
                    val distInfoFilename = if (file.name.endsWith(".dist-info")) file.name else file.parentFile.name
                    val distInfoMatch = distInfoPatternRegex.find(distInfoFilename)
                    
                    if (distInfoMatch != null) {
                        packageName = distInfoMatch.groupValues[1]
                        version = distInfoMatch.groupValues[2]
                    } else if (file.name == "METADATA") {
                        // 尝试从METADATA文件获取信息
                        try {
                            val metadataContent = file.readText()
                            val nameMatch = nameRegex.find(metadataContent)
                            val versionMatch = versionRegex.find(metadataContent)
                            
                            packageName = nameMatch?.groupValues?.get(1)?.trim() ?: dirName.removeSuffix(".dist-info")
                            version = versionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                        } catch (e: Exception) {
                            // 解析失败，使用目录名
                            packageName = dirName.removeSuffix(".dist-info")
                            version = "unknown"
                        }
                    } else {
                        // 其他文件，使用目录名
                        val nameParts = dirName.removeSuffix(".dist-info").split("-")
                        if (nameParts.size >= 2 && nameParts.last().matches(versionNumberRegex)) {
                            packageName = nameParts.dropLast(1).joinToString("-")
                            version = nameParts.last()
                        } else {
                            packageName = dirName.removeSuffix(".dist-info")
                            version = "unknown"
                        }
                    }
                } else {
                    // 无法确定包信息
                    packageName = file.nameWithoutExtension
                    version = "unknown"
                }
            }
            // PKG-INFO 文件
            file.name == "PKG-INFO" -> {
                // 尝试从PKG-INFO文件内容解析
                try {
                    val pkgInfoContent = file.readText()
                    val nameMatch = nameRegex.find(pkgInfoContent)
                    val versionMatch = versionRegex.find(pkgInfoContent)
                    
                    packageName = nameMatch?.groupValues?.get(1)?.trim() ?: file.parentFile.name
                    version = versionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                } catch (e: Exception) {
                    // 尝试从父目录获取信息
                    if (file.parentFile != null) {
                        val parentName = file.parentFile.name
                        if (parentName.endsWith(".egg-info") || parentName == "EGG-INFO") {
                            val eggInfoFilename = if (parentName.endsWith(".egg-info")) parentName else "$parentName.egg-info"
                            val eggInfoMatch = eggInfoPatternRegex.find(eggInfoFilename)
                            
                            if (eggInfoMatch != null) {
                                packageName = eggInfoMatch.groupValues[1]
                                version = eggInfoMatch.groupValues[2]
                            } else {
                                packageName = parentName.removeSuffix(".egg-info")
                                version = "unknown"
                            }
                        } else {
                            // 尝试从目录名解析
                            val dirParts = parentName.split("-")
                            if (dirParts.size >= 2 && dirParts.last().matches(versionNumberRegex)) {
                                packageName = dirParts.dropLast(1).joinToString("-")
                                version = dirParts.last()
                            } else {
                                packageName = parentName
                                version = "unknown"
                            }
                        }
                    } else {
                        packageName = "unknown"
                        version = "unknown"
                    }
                }
            }
            // setup.py 文件
            file.name == "setup.py" && file.parentFile?.name?.contains("-") == true -> {
                // 尝试从目录名解析
                val dirName = file.parentFile.name
                val setupFilename = if (file.name.endsWith(".py")) file.name else file.nameWithoutExtension
                val setupMatch = setupPatternRegex.find(setupFilename)
                
                if (setupMatch != null) {
                    packageName = setupMatch.groupValues[1]
                    version = setupMatch.groupValues[2]
                } else {
                    // 尝试解析源代码
                    try {
                        val setupContent = file.readText()
                        val nameMatch = setupNameRegex.find(setupContent)
                        val versionMatch = setupVersionRegex.find(setupContent)
                        
                        packageName = nameMatch?.groupValues?.get(1)?.trim() ?: dirName
                        version = versionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                    } catch (e: Exception) {
                        val dirParts = dirName.split("-")
                        if (dirParts.size >= 2 && dirParts.last().matches(versionNumberRegex)) {
                            packageName = dirParts.dropLast(1).joinToString("-")
                            version = dirParts.last()
                        } else {
                            packageName = dirName
                            version = "unknown"
                        }
                    }
                }
            }
            // 其他特殊文件
            file.name == "requires.txt" || file.name == "installed-files.txt" || file.name == "direct_url.json" -> {
                // 从父目录尝试解析
                if (file.parentFile != null) {
                    val parentName = file.parentFile.name
                    
                    if (parentName.endsWith(".egg-info")) {
                        val eggInfoFilename = if (parentName.endsWith(".egg-info")) parentName else "$parentName.egg-info"
                        val eggInfoMatch = eggInfoPatternRegex.find(eggInfoFilename)
                        
                        if (eggInfoMatch != null) {
                            packageName = eggInfoMatch.groupValues[1]
                            version = eggInfoMatch.groupValues[2]
                        } else {
                            packageName = parentName.removeSuffix(".egg-info")
                            version = "unknown"
                        }
                    } else if (parentName.endsWith(".dist-info")) {
                        val distInfoFilename = if (file.name.endsWith(".dist-info")) file.name else file.parentFile.name
                        val distInfoMatch = distInfoPatternRegex.find(distInfoFilename)
                        
                        if (distInfoMatch != null) {
                            packageName = distInfoMatch.groupValues[1]
                            version = distInfoMatch.groupValues[2]
                        } else {
                            packageName = parentName.removeSuffix(".dist-info")
                            version = "unknown"
                        }
                    } else {
                        packageName = parentName
                        version = "unknown"
                    }
                } else {
                    packageName = file.nameWithoutExtension
                    version = "unknown"
                }
            }
            // 其他未知类型
            else -> {
                // 如果是顶级目录，尝试从子目录查找元数据
                val metadataFile = File(packageDir, "PKG-INFO")
                if (metadataFile.exists()) {
                    try {
                        val pkgInfoContent = metadataFile.readText()
                        val nameMatch = nameRegex.find(pkgInfoContent)
                        val versionMatch = versionRegex.find(pkgInfoContent)
                        
                        packageName = nameMatch?.groupValues?.get(1)?.trim() ?: file.nameWithoutExtension
                        version = versionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                    } catch (e: Exception) {
                        packageName = file.nameWithoutExtension
                        version = "unknown"
                    }
                } else {
                    // 尝试从目录名解析
                    if (packageDir != null && packageDir.name.contains("-")) {
                        val dirMatch = sdistPatternRegex.find(packageDir.name)
                        
                        if (dirMatch != null) {
                            packageName = dirMatch.groupValues[1]
                            version = dirMatch.groupValues[2]
                        } else {
                            packageName = file.nameWithoutExtension
                            version = "unknown"
                        }
                    } else {
                        packageName = file.nameWithoutExtension
                        version = "unknown"
                    }
                }
            }
        }
        
        // 规范化包名（PEP 503：把连字符、下划线、点都转换为连字符）
        val normalizedPackageName = packageName.lowercase().replace(pythonPackageNormalizeRegex, "-")
        
        return PkgData(
            relativePath = relativePath ?: "",
            packageName = "$normalizedPackageName${getPackageNameSeparator()}$version",
            packageDir = packageDir
        )
    }

    override fun getDependencyType(): DependencyType {
        return DependencyType.PIP
    }

    /**
     * 实现新的 scanRepository 方法，使用 ConfigOptions 对象
     */
    override fun scanRepository(
        dir: File, 
        onDirFound: (File, String, PkgData) -> Unit, 
        configOptions: ConfigOptions
    ) {
        val targetPackageName = configOptions.targetPackage.takeIf { it.isNotEmpty() }?.lowercase()
        val pathPkgDataMap = fetchPkgMap(dir)

        pathPkgDataMap.forEach { (path, pkgData) ->
            // 检查是否是invalid文件（损坏或未完成的下载）
            val isInvalidPackage = pkgData.invalid
            
            // 检查是否是snapshot版本（预发布版本：含有dev, a, alpha, b, beta, rc等标记）
            val isSnapshotVersion = pkgData.packageName.contains(snapshotVersionRegex)
            
            // 检查是否匹配指定的包名
            val packageNameParts = pkgData.packageName.split(":")
            val packageNameOnly = packageNameParts[0].lowercase()
            
            // Python包通常有下划线和连字符的变体，所以我们需要规范化进行比较
            val normalizedPackageName = packageNameOnly.replace(pythonPackageNormalizeRegex, "-")
            val normalizedTargetName = targetPackageName?.replace(pythonPackageNormalizeRegex, "-") ?: ""
            
            val isTargetPackage = when {
                targetPackageName == null -> false // 如果没有指定包名，则不视为匹配
                normalizedPackageName.startsWith(normalizedTargetName) -> true // 前缀匹配
                packageNameOnly.endsWith(".$targetPackageName") -> true  // 处理子包情况
                packageNameOnly.startsWith("$targetPackageName.") -> true  // 处理父包情况
                else -> false
            }
            
            // 处理平台特定的二进制包（wheel包）
            val isNativeBinary = pkgData.packageDir.name.matches(nativeBinaryRegex)
            
            // 筛选逻辑
            val shouldInclude = when {
                configOptions.showInvalidPackages && isInvalidPackage -> true
                configOptions.targetPackage.isNotEmpty() && isTargetPackage -> true
                configOptions.includeSnapshot && isSnapshotVersion -> true
                configOptions.showPlatformSpecificBinaries && isNativeBinary -> true
                else -> false
            }

            // 设置匹配类型
            val matchType = when {
                isInvalidPackage -> "invalid"
                isTargetPackage -> "matched"
                isSnapshotVersion -> "prerelease"
                isNativeBinary -> "platform"
                else -> "unknown"
            }

            if (shouldInclude) {
                onDirFound(pkgData.packageDir, matchType, pkgData)
            }
        }
    }

    /**
     * 尝试查找系统Python的site-packages目录
     */
    private fun findSystemPythonSitePackages(): String? {
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        val userHome = System.getProperty("user.home")
        
        // 常见的系统Python site-packages位置
        val possiblePaths = when {
            osName.contains("win") -> listOf(
                "${System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"}/Programs/Python/Python3*/Lib/site-packages",
                "C:/Program Files/Python3*/Lib/site-packages",
                "C:/Python3*/Lib/site-packages"
            )
            osName.contains("mac") -> listOf(
                "/Library/Frameworks/Python.framework/Versions/3.*/lib/python3.*/site-packages",
                "/usr/local/lib/python3.*/site-packages",
                "$userHome/Library/Python/3.*/lib/python/site-packages"
            )
            else -> listOf( // 假设是Linux/Unix系统
                "/usr/lib/python3/dist-packages",
                "/usr/lib/python3.*/site-packages",
                "/usr/local/lib/python3.*/site-packages",
                "$userHome/.local/lib/python3.*/site-packages"
            )
        }
        
        // 尝试找到第一个存在的路径
        for (pattern in possiblePaths) {
            // 使用简单的通配符匹配
            val globPattern = pattern.replace("*", "?").replace("?", "*")
            val matchingDirs = File(pattern.substringBeforeLast("/")).listFiles { file ->
                val regex = globPattern.substringAfterLast("/").replace("*", ".*")
                file.isDirectory && file.name.matches(Regex(regex))
            }
            
            if (matchingDirs?.isNotEmpty() == true) {
                return matchingDirs[0].absolutePath
            }
        }
        
        return null
    }

    /**
     * 覆盖父类方法，返回@作为PIP包的分隔符
     */
    override fun getPackageNameSeparator(): String {
        return "@"
    }

    /**
     * 检查是否需要用户手动选择Conda安装目录
     * @return 如果需要用户选择则返回true
     */
    fun isCondaSelectionNeeded(): Boolean {
        return environmentType == PythonEnvironmentType.CONDA && 
               (condaInstallPath == null || environmentPath == null)
    }
    
    /**
     * 处理用户选择的Conda目录
     * @param selectedDir 用户选择的目录
     * @return 找到的site-packages路径，如果无效则返回null
     */
    fun processSelectedCondaDirectory(selectedDir: File): String? {
        // 检查是否是有效的Conda目录
        if (!isValidCondaDir(selectedDir.absolutePath)) {
            return null
        }
        
        // 设置conda安装路径
        condaInstallPath = selectedDir.absolutePath
        
        // 查找site-packages目录
        val sitePkgsPath = findSitePackagesInConda(condaInstallPath!!)
        if (sitePkgsPath != null) {
            environmentPath = sitePkgsPath
            return sitePkgsPath
        }
        
        return null
    }

    /**
     * 检测并初始化Python环境
     * 该方法会检测项目中常见的Python环境标志并设置相应的环境类型
     * @return 检测到的环境类型
     */
    fun detectPythonEnvironment(): PythonEnvironmentType {
        // 如果已经设置了环境类型，则不再重新检测
        if (environmentPath != null && environmentType != PythonEnvironmentType.SYSTEM) {
            return environmentType
        }
        
        val projectPath = project.basePath ?: return PythonEnvironmentType.SYSTEM
        
        // 检测项目中的虚拟环境
        when {
            // 检查venv目录
            File("$projectPath/venv").exists() || 
            File("$projectPath/.venv").exists() -> {
                environmentType = PythonEnvironmentType.VENV
                val venvDir = if (File("$projectPath/venv").exists()) "$projectPath/venv" else "$projectPath/.venv"
                environmentPath = findSitePackagesInVenv(venvDir)
            }
            // 检查Pipenv
            File("$projectPath/Pipfile").exists() -> {
                environmentType = PythonEnvironmentType.PIPENV
                // 尝试获取Pipenv的虚拟环境路径
                val pipenvVenvPath = getPipenvVirtualEnvPath(projectPath)
                if (pipenvVenvPath != null) {
                    environmentPath = findSitePackagesInVenv(pipenvVenvPath)
                }
            }
            // 检查Conda环境
            File("$projectPath/environment.yml").exists() || 
            File("$projectPath/conda-env.yml").exists() -> {
                environmentType = PythonEnvironmentType.CONDA
                // 尝试从conda配置文件中获取环境名称
                val condaEnvName = getCondaEnvironmentName(projectPath)
                if (condaEnvName != null) {
                    val condaEnvPath = getCondaEnvironmentPath(condaEnvName)
                    if (condaEnvPath != null) {
                        environmentPath = findSitePackagesInVenv(condaEnvPath)
                    }
                } else {
                    // 如果找不到环境名称，尝试检测常见的conda安装路径
                    detectCondaInstallation()
                }
            }
            // 检查系统Python
            else -> {
                environmentType = PythonEnvironmentType.SYSTEM
                // 尝试查找系统Python路径
                environmentPath = findSystemPythonSitePackages()
            }
        }
        
        return environmentType
    }

    /**
     * 统一查找Python环境中的site-packages目录
     * @param envPath Python环境路径（venv、conda或pipenv）
     * @return site-packages目录路径，如果找不到则返回null
     */
    private fun findSitePackagesInVenv(envPath: String): String? {
        // 检查标准路径结构
        val libDir = if (File("$envPath/lib").exists()) "lib" else "Lib"
        
        // 如果lib目录存在，查找python*目录
        val libDirFile = File("$envPath/$libDir")
        if (libDirFile.exists() && libDirFile.isDirectory) {
            val pythonDirs = libDirFile.listFiles { file -> 
                file.isDirectory && file.name.startsWith("python") 
            }
            
            if (pythonDirs?.isNotEmpty() == true) {
                val sitePkgs = "${pythonDirs[0].absolutePath}/site-packages"
                if (File(sitePkgs).exists()) {
                    return sitePkgs
                }
            }
        }
        
        // 尝试直接查找常见的site-packages路径
        val possiblePaths = listOf(
            "$envPath/Lib/site-packages",
            "$envPath/lib/site-packages",
            "$envPath/lib/python3.8/site-packages",
            "$envPath/lib/python3.9/site-packages",
            "$envPath/lib/python3.10/site-packages",
            "$envPath/lib/python3.11/site-packages",
            "$envPath/lib/python3.12/site-packages"
        )
        
        return possiblePaths.find { File(it).exists() && File(it).isDirectory }
    }
} 