package com.github.zijing66.dependencymanager.ui

import com.github.zijing66.dependencymanager.models.*
import com.github.zijing66.dependencymanager.services.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<DependencyCleanupToolWindow>()

class DependencyCleanupToolWindow : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        LOG.info("Creating tool window content")
        // 使用兼容性工具类创建内容
        CompatibilityUtil.createContent(
            toolWindow.contentManager,
            DependencyCleanupPanel(project),
            "",
            false
        )
    }

    override fun shouldBeAvailable(project: Project) = true
}

private class PreviewTableModel : AbstractTableModel() {
    private val columns = arrayOf("", "Package Name", "Match Type", "Size", "Last Modified")

    private var data: List<CleanupPreview> = emptyList()

    fun setData(newData: List<CleanupPreview>) {
        // 默认不选中所有项
        data = newData.map { it.copy(selected = false) }
        fireTableDataChanged()
    }

    fun getItemAt(rowIndex: Int): CleanupPreview {
        return data[rowIndex]
    }

    fun getSelectedItems(): List<CleanupPreview> {
        return data.filter { it.selected }
    }

    override fun getRowCount() = data.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    override fun getColumnClass(columnIndex: Int) = when (columnIndex) {
        0 -> Boolean::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(timestamp))
    }

    private fun formatFileSize(size: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${df.format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${df.format(size / (1024.0 * 1024.0))} MB"
            else -> "${df.format(size / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = data[rowIndex]
        return when (columnIndex) {
            0 -> item.selected
            1 -> item.packageName
            2 -> item.matchType
            3 -> formatFileSize(item.fileSize)
            4 -> formatDate(item.lastModified)
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0) {
            data[rowIndex].selected = (aValue as? Boolean) ?: false
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    // 添加全选/全不选方法
    fun setAllSelected(selected: Boolean) {
        data = data.map { it.copy(selected = selected) }
        fireTableDataChanged()
    }
}

private class DependencyCleanupPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val previewTable: JBTable
    private val previewModel: PreviewTableModel = PreviewTableModel()

    private val initialized = AtomicBoolean(false)

    private val headerCheckBox = JCheckBox()
    private lateinit var configService: IConfigService
    private lateinit var currentDependencyType: DependencyType
    private lateinit var snapshotCheckBox: JCheckBox
    private lateinit var invalidPackageCheckBox: JCheckBox
    private lateinit var groupArtifactField: JTextField
    private lateinit var progressBar: JProgressBar
    private lateinit var statusLabel: JLabel
    // 添加Python环境类型相关变量
    private lateinit var pythonEnvTypeGroup: ButtonGroup
    private lateinit var systemRadio: JRadioButton
    private lateinit var venvRadio: JRadioButton
    private lateinit var condaRadio: JRadioButton
    private lateinit var pipenvRadio: JRadioButton
    private lateinit var pathField: JTextField
    private var currentRepoPath: String = ""
    private lateinit var refreshButton: JButton
    private lateinit var previewButton: JButton
    private lateinit var cleanButton: JButton
    private var pythonEnvTypePanel: JPanel? = null

    init {
        previewTable = JBTable(previewModel).apply {
            setShowGrid(true)
            gridColor = JBColor.border()
            isStriped = true
            autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS

            // 设置列宽
            columnModel.apply {
                getColumn(0).preferredWidth = 30 // Checkbox
                getColumn(0).maxWidth = 30
                getColumn(1).preferredWidth = 300 // Package Name
                getColumn(2).preferredWidth = 120 // Match Type
                getColumn(2).maxWidth = 120  // 增加Match Type列宽以便完整显示"prerelease"
                getColumn(3).preferredWidth = 100 // Size
                getColumn(3).maxWidth = 100
                getColumn(4).preferredWidth = 150 // Last Modified
                getColumn(4).maxWidth = 150
            }
            // 添加单元格编辑器
            columnModel.getColumn(0).cellEditor = object : DefaultCellEditor(JCheckBox()) {
                override fun getTableCellEditorComponent(
                    table: JTable?,
                    value: Any?,
                    isSelected: Boolean,
                    row: Int,
                    column: Int
                ): Component {
                    return (super.getTableCellEditorComponent(
                        table,
                        value,
                        isSelected,
                        row,
                        column
                    ) as JCheckBox).apply {
                        horizontalAlignment = SwingConstants.CENTER
                    }
                }
            }
            // 设置渲染器
            columnModel.getColumn(0).cellRenderer = createBooleanRenderer()
            columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, 
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int
                ): Component {
                    val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    horizontalAlignment = SwingConstants.CENTER
                    
                    if (!isSelected) {
                        foreground = when (value) {
                            "matched" -> JBColor.GREEN.darker()
                            "prerelease", "snapshot" -> JBColor.ORANGE // 统一prerelease和snapshot的样式
                            "native" -> JBColor.BLUE
                            else -> JBColor.GRAY
                        }
                    }
                    
                    return c
                }
            }

            // 修改表头复选框引用
            val header = tableHeader
            headerCheckBox.apply {  // 使用成员变量
                addActionListener {
                    previewModel.setAllSelected(isSelected)
                }
            }

            columnModel.getColumn(0).headerRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?,
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int
                ): Component {
                    return headerCheckBox.apply {
                        horizontalAlignment = SwingConstants.CENTER
                    }
                }
            }

            // 修改点击事件处理
            header.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (header.columnAtPoint(e.point) == 0) {
                        headerCheckBox.isSelected = !headerCheckBox.isSelected
                        previewModel.setAllSelected(headerCheckBox.isSelected)
                    }
                }
            })
            // 添加双击事件处理
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) { // 检查是否双击
                        val row = rowAtPoint(e.point)
                        val column = columnAtPoint(e.point)
                        if (column == 1) { // 检查是否在Package Name列
                            openExplorerForPackage(previewModel.getItemAt(row)) // 打开资源管理器
                        }
                    }
                }
            })

            // 添加鼠标悬浮提示
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val column = columnAtPoint(e.point)
                    toolTipText = if (column == 1 && row >= 0) { // 检查是否在Package Name列
                        "Double click to open Explorer"
                    } else {
                        null // 清除提示
                    }
                }
            })
        }
        setupUI()
    }

    private fun createBooleanRenderer(): TableCellRenderer {
        return object : DefaultTableCellRenderer() {
            private val checkBox = JCheckBox()

            init {
                horizontalAlignment = SwingConstants.CENTER
            }

            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                if (value is Boolean) {
                    checkBox.isSelected = value
                    checkBox.horizontalAlignment = SwingConstants.CENTER
                    checkBox.background = if (isSelected) table.selectionBackground else table.background
                    return checkBox
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            }
        }
    }

    private fun judgeForDependencyType(func: () -> Boolean, dependencyTypeList: Array<DependencyType>) : Boolean {
        for (dependencyType in dependencyTypeList) {
            if (dependencyTypeList.contains(currentDependencyType)) {
                return func()
            }
        }
        return false
    }

    private fun validForDependencyType(func: () -> Unit, dependencyTypeList: Array<DependencyType>) {
        for (dependencyType in dependencyTypeList) {
            if (dependencyType == DependencyType.UNKNOWN || !dependencyTypeList.contains(currentDependencyType)) {
                continue
            }
            func()
            break // 只执行一次
         }
        return
    }

    private fun setupUI() {
        // Top panel with controls
        val controlPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(5)
        }
        add(controlPanel, BorderLayout.NORTH)

        val detector = DependencyManagerDetector()
        val dependencyType = detector.detectDependencyType(project)

        updateUIComponents(controlPanel, dependencyType)

        // Table panel
        val tablePanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(JBScrollPane(previewTable).apply {
                preferredSize = Dimension(preferredSize.width, 300) // 固定初始高度
            }, BorderLayout.CENTER)
        }
        add(tablePanel, BorderLayout.CENTER)
    }

    private fun updateUIComponents(controlPanel: JPanel, chosenDependencyType : DependencyType) {
        currentDependencyType = chosenDependencyType
        // 清空当前组件
        controlPanel.removeAll()
        // 清空预览数据
        previewModel.setData(emptyList())
        
        // 重置配置项
        ConfigOptions.getInstance().resetAll()

        // 创建一个水平布局的面板
        val topRowPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT  // 确保面板左对齐
            add(JLabel("Dependency manager: $chosenDependencyType").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createHorizontalStrut(10)) // 添加间隔
            // 添加下拉菜单供用户选择依赖管理器
            add(JLabel("Switch to: ").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            val dependencyTypeList = DependencyType.values().filter { it != DependencyType.UNKNOWN && it != chosenDependencyType }.toTypedArray()
            val dependencyTypeComboBox = JComboBox(dependencyTypeList).apply {
                alignmentX = Component.LEFT_ALIGNMENT  // 确保下拉框左对齐
                addActionListener {
                    val dependencyType = selectedItem as DependencyType
                    updateUIComponents(controlPanel, dependencyType)
                }
            }
            add(dependencyTypeComboBox)
            add(Box.createHorizontalGlue())  // 添加水平弹性空间，确保组件靠左对齐
        }

        controlPanel.add(topRowPanel) // 将水平面板添加到控制面板

        // 初始化配置服务
        try {
            updateConfigService(chosenDependencyType)
        } catch (e: Exception) {
            LOG.info(String.format("Failed to initialize config service: {}", e.message))
            return
        }

        // 添加仓库路径显示
        currentRepoPath = configService.getLocalRepository(false)

        val pathStatusLabel = JLabel().apply {
            foreground = JBColor.RED
            font = font.deriveFont(font.size2D - 1f)
            alignmentX = Component.LEFT_ALIGNMENT  // 确保标签左对齐
        }

        // 为PIP模式添加Python环境类型选择面板
        if (chosenDependencyType == DependencyType.PIP) {
            pythonEnvTypePanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createTitledBorder("Python Environment Type")
                
                // 创建单选按钮组
                pythonEnvTypeGroup = ButtonGroup()
                
                systemRadio = JRadioButton("System").apply {
                    pythonEnvTypeGroup.add(this)
                    addActionListener { updatePythonEnvironment(PythonEnvironmentType.SYSTEM) }
                }
                
                venvRadio = JRadioButton("Virtual Env").apply {
                    pythonEnvTypeGroup.add(this)
                    addActionListener { updatePythonEnvironment(PythonEnvironmentType.VENV) }
                }
                
                condaRadio = JRadioButton("Conda").apply {
                    pythonEnvTypeGroup.add(this)
                    addActionListener { updatePythonEnvironment(PythonEnvironmentType.CONDA) }
                }
                
                pipenvRadio = JRadioButton("Pipenv").apply {
                    pythonEnvTypeGroup.add(this)
                    addActionListener { updatePythonEnvironment(PythonEnvironmentType.PIPENV) }
                }
                
                add(systemRadio)
                add(Box.createHorizontalStrut(10))
                add(venvRadio)
                add(Box.createHorizontalStrut(10))
                add(condaRadio)
                add(Box.createHorizontalStrut(10))
                add(pipenvRadio)
                add(Box.createHorizontalGlue())
            }
            
            controlPanel.add(pythonEnvTypePanel)
            controlPanel.add(Box.createVerticalStrut(5))
            
            // 使用PIPConfigService的detectPythonEnvironment方法初始化默认选项
            val detectedType: PythonEnvironmentType
            val pipConfigService = configService as? PIPConfigService
            if (pipConfigService != null) {
                // 检测当前环境并获取类型
                detectedType = pipConfigService.detectPythonEnvironment()
                pipConfigService.setEnvironmentType(detectedType)
                // 根据检测到的环境类型设置选中状态
                when (detectedType) {
                    PythonEnvironmentType.SYSTEM -> systemRadio.isSelected = true
                    PythonEnvironmentType.VENV -> venvRadio.isSelected = true
                    PythonEnvironmentType.CONDA -> condaRadio.isSelected = true
                    PythonEnvironmentType.PIPENV -> pipenvRadio.isSelected = true
                }
            } else {
                systemRadio.isSelected = true // 默认选中
            }
        }

        previewButton = JButton("Preview Cleanup").apply {
            addActionListener { loadPreview(configService) }
        }

        cleanButton = JButton("Clean Selected").apply {
            isEnabled = false
            addActionListener { performCleanup(configService) }
        }

        pathField = JTextField(currentRepoPath, 40).apply {
            validForDependencyType({
                toolTipText = "Maven local repository path (writable directory)"
            } , arrayOf(DependencyType.MAVEN))
            validForDependencyType({
                toolTipText = "Gradle local repository path (writable directory)"
            } , arrayOf(DependencyType.GRADLE))
            validForDependencyType({
                toolTipText = "PIP packages directory (writable directory)"
            } , arrayOf(DependencyType.PIP))
            validForDependencyType({
                toolTipText = "NPM packages directory (writable directory)"
            } , arrayOf(DependencyType.NPM))
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2)
            )
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateButtonState()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateButtonState()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateButtonState()
                private fun updateButtonState() {
                    val changed = text != currentRepoPath
                    refreshButton.isEnabled = changed // 使用外部作用域的refreshButton
                    previewButton.isEnabled = !changed
                    cleanButton.isEnabled = !changed && previewModel.getSelectedItems().isNotEmpty()
                }
            })
        }

        refreshButton = JButton("Refresh").apply { // 初始化已声明的按钮
            isEnabled = false
            addActionListener {
                try {
                    val newPath = pathField.text.ifBlank {
                        // 当输入框为空时，获取系统默认路径
                        val defaultPath = configService.getLocalRepository(true)

                        // 处理PIP配置服务特殊情况
                        if (currentDependencyType == DependencyType.PIP) {
                            val pipConfigService = configService as? PIPConfigService
                            if (defaultPath.isNotEmpty() && File(defaultPath).exists()) {
                                pipConfigService?.updateLocalRepository(defaultPath)
                                defaultPath
                            } else {
                                // 如果路径不存在，清空路径
                                ""
                            }
                        } else {
                            // 其他依赖类型正常处理
                            configService.updateLocalRepository(defaultPath)
                            defaultPath
                        }
                    }
                    
                    // 更新当前路径和输入框显示
                    currentRepoPath = newPath
                    pathField.text = newPath
                    
                    // 仅在路径非空时更新
                    if (newPath.isNotEmpty()) {
                        configService.updateLocalRepository(newPath)
                    }
                    
                    pathStatusLabel.text = ""
                    isEnabled = false
                    previewButton.isEnabled = newPath.isNotEmpty() // 仅在路径非空时启用预览按钮
                } catch (e: Exception) {
                    pathStatusLabel.text = "Invalid path: ${e.message}"
                    previewButton.isEnabled = false
                    cleanButton.isEnabled = false
                }
            }
        }

        val repoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            
            // 创建一个固定宽度的标签面板，确保标签对齐
            val labelPanel = JPanel(BorderLayout()).apply {
                preferredSize = Dimension(120, 25)  // 设置固定宽度
                validForDependencyType({add(JLabel("Maven repository: "), BorderLayout.WEST)}, arrayOf(DependencyType.MAVEN))
                validForDependencyType({add(JLabel("Gradle repository: "), BorderLayout.WEST)}, arrayOf(DependencyType.GRADLE))
                validForDependencyType({add(JLabel("NPM cache: "), BorderLayout.WEST)}, arrayOf(DependencyType.NPM))
                validForDependencyType({add(JLabel("PIP cache: "), BorderLayout.WEST)}, arrayOf(DependencyType.PIP))
            }
            add(labelPanel)
            
            add(Box.createHorizontalStrut(5))
            add(pathField.apply {
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) // 允许横向扩展
            })
            add(Box.createHorizontalStrut(5))
            add(refreshButton)
        }

        if (DependencyType.PIP == currentDependencyType) {
            val selectedButton = getSelectedButton(pythonEnvTypeGroup)
            updatePythonEnvironment(
                when (selectedButton?.text) {
                    "System" -> PythonEnvironmentType.SYSTEM
                    "Virtual Env" -> PythonEnvironmentType.VENV
                    "Conda" -> PythonEnvironmentType.CONDA
                    "Pipenv" -> PythonEnvironmentType.PIPENV
                    else -> PythonEnvironmentType.SYSTEM
                }
            )
        }
        
        // 使用新定义的方法创建过滤面板
        val filterPanel = createFilterPanel()
        
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(previewButton)
            add(Box.createHorizontalStrut(10))
            add(cleanButton)
            add(Box.createHorizontalGlue())
        }

        controlPanel.apply {
            add(repoPanel)
            add(Box.createVerticalStrut(5))
            
            // 为错误消息添加一个面板，保持与其他组件的对齐
            val errorPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(Box.createRigidArea(Dimension(120, 0)))
                add(pathStatusLabel)
            }
            add(errorPanel)
            
            add(Box.createVerticalStrut(10))
            // 添加过滤面板，已经有了自己的边距
            add(filterPanel)
            add(Box.createVerticalStrut(5))
            add(buttonPanel)
            add(Box.createVerticalStrut(5))

            progressBar = JProgressBar()
            // 为进度条添加一个面板，保持与其他组件的对齐
            val progressPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(progressBar.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    isVisible = false
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)  // 允许横向扩展
                })
            }
            add(progressPanel)
            
            add(Box.createVerticalStrut(5))

            statusLabel = JLabel()
            // 为状态标签添加一个面板，保持与其他组件的对齐
            val statusPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(statusLabel.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
            add(statusPanel)
        }
        // 在表格模型添加监听器
        previewModel.addTableModelListener {
            val hasSelection = previewModel.getSelectedItems().isNotEmpty()
            cleanButton.isEnabled = hasSelection
        }

        previewButtonStat(previewButton)

        initialized.set(true)
        // 刷新面板
        controlPanel.revalidate()
        controlPanel.repaint()
    }

    private fun previewButtonStat(previewButton: JButton) {
        val configOptions = ConfigOptions.getInstance()
        if (configOptions.includeSnapshot || configOptions.showInvalidPackages || configOptions.showPlatformSpecificBinaries || configOptions.targetPackage.isNotEmpty()) {
            previewButton.isEnabled = true
        } else {
            previewButton.isEnabled = false
        }
    }

    // 获取 ButtonGroup 中的选中按钮
    private fun getSelectedButton(buttonGroup: ButtonGroup): AbstractButton? {
        for (button in buttonGroup.elements) {
            if (button.isSelected) {
                return button
            }
        }
        return null
    }

    private fun updateConfigService(dependencyType: DependencyType) {
        configService = when (dependencyType) {
            DependencyType.MAVEN -> MavenConfigService(project)
            DependencyType.GRADLE -> GradleConfigService(project)
            DependencyType.NPM -> NPMConfigService(project)
            DependencyType.PIP -> PIPConfigService(project)
            else -> throw IllegalArgumentException("Unsupported dependency type: $dependencyType")
        }
    }

    private fun loadPreview(configService: IConfigService) {
        val configOptions = ConfigOptions.getInstance()
        
        // 更新配置选项
        configOptions.includeSnapshot = snapshotCheckBox.isSelected
        configOptions.targetPackage = groupArtifactField.text.takeIf { it.isNotEmpty() } ?: ""
        
        judgeForDependencyType({
            if (!configOptions.showPlatformSpecificBinaries && !configOptions.includeSnapshot && 
                configOptions.targetPackage.isEmpty() && !configOptions.showInvalidPackages) {
                Messages.showErrorDialog(
                    project,
                    "Please choose at least one filter option",
                    "Input Error"
                )
                return@judgeForDependencyType true
            }
            return@judgeForDependencyType false
        }, arrayOf(DependencyType.MAVEN, DependencyType.GRADLE, DependencyType.NPM, DependencyType.PIP)) && return

        progressBar.isVisible = true
        progressBar.isIndeterminate = true
        statusLabel.text = "Loading preview..."

        configService.previewCleanup(
            configOptions,
            onProgress = { current, total ->
                SwingUtilities.invokeLater {  // 添加Swing线程调度
                    progressBar.value = (current.toFloat() / total * 100).toInt()
                    statusLabel.text = "Scanning files: $current / $total completed"
                }
            },
            onComplete = { currentPreview ->
                SwingUtilities.invokeLater {
                    previewModel.setData(currentPreview.previewItems)
                    headerCheckBox.isSelected = false
                    val totalSize = formatFileSize(currentPreview.totalSize)
                    statusLabel.text = "Scanned ${currentPreview.totalScannedCount} files, Found ${currentPreview.totalCount} files, (Total size: $totalSize)"  // 添加总大小标识
                    progressBar.isVisible = false
                    previewTable.parent.parent.isVisible = true
                }
            }
        )

    }

    private fun performCleanup(configService: IConfigService) {
        val selectedItems = previewModel.getSelectedItems()
        if (selectedItems.isEmpty()) return

        progressBar.isVisible = true
        progressBar.isIndeterminate = false
        progressBar.value = 0

        configService.cleanupRepository(
            project = project,
            selectedItems = selectedItems,
            onProgress = { current, total ->
                SwingUtilities.invokeLater {  // 添加Swing线程调度
                    progressBar.value = (current.toFloat() / total * 100).toInt()
                    statusLabel.text = "Deleting files: $current/$total completed"
                }
            },
            onComplete = { results ->
                SwingUtilities.invokeLater {
                    progressBar.isVisible = false
                    val success = results.count { it.success }
                    statusLabel.text = "Cleanup complete: $success/${results.size} files cleaned"
                    loadPreview(configService) // Refresh the preview
                }
            }
        )
    }

    private fun formatFileSize(size: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${df.format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${df.format(size / (1024.0 * 1024.0))} MB"
            else -> "${df.format(size / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    private fun openExplorerForPackage(item: CleanupPreview) {
        // 根据包名获取路径并打开资源管理器
        val path = item.path // 需要实现此方法以获取路径
        val file = File(path)
        if (file.exists() && file.isDirectory) {
            // 使用系统命令打开资源管理器
            val command = when {
                System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win") -> "explorer"
                System.getProperty("os.name").lowercase(Locale.getDefault()).contains("mac") -> "open"
                else -> "xdg-open" // Linux
            }
            Runtime.getRuntime().exec(arrayOf(command, file.path))
        }
    }

    // 创建统一样式的选项面板 - 用于复选框选项
    private fun createFilterOption(labelText: String, checkBoxText: String, checkBoxConfig: JCheckBox.() -> Unit): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
            add(JLabel(labelText))
            add(JCheckBox(checkBoxText).apply(checkBoxConfig))
            
            // 设置最大宽度以确保面板不会过宽
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }
    
    private fun createFilterPanel(): JPanel {
        val configOptions = ConfigOptions.getInstance()
        
        return JPanel().apply {
            // 使用垂直BoxLayout排列所有选项
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            // 设置一个合理的初始高度
            preferredSize = Dimension(preferredSize.width, 150) // 增加高度从120到150以显示所有选项
            border = JBUI.Borders.empty(5, 0)
            
            // 直接添加SNAPSHOT/Prerelease选项（第一行）
            add(createFilterOption("Choose:", "SNAPSHOT") {
                snapshotCheckBox = this  // 使用this引用JCheckBox实例
                validForDependencyType({
                    text = "SNAPSHOT"
                    toolTipText = "Include SNAPSHOT packages"
                }, arrayOf(DependencyType.MAVEN, DependencyType.GRADLE))
                validForDependencyType({
                    text = "Prerelease"
                    toolTipText = "Include prerelease packages (alpha, beta, rc, dev, etc.)"
                }, arrayOf(DependencyType.NPM, DependencyType.PIP))
                addActionListener {
                    // 更新配置
                    configOptions.includeSnapshot = isSelected
                    previewButtonStat(previewButton)
                }
            }.apply { 
                alignmentX = Component.LEFT_ALIGNMENT 
            })
            
            // 添加垂直间距
            add(Box.createVerticalStrut(5))
            
            // 无效包选项（新增的第二行）
            add(createFilterOption("Choose:", "Invalid") {
                invalidPackageCheckBox = this  // 使用this引用JCheckBox实例
                toolTipText = "Show invalid packages (corrupted or incomplete downloads)"
                addActionListener {
                    // 更新配置
                    configOptions.showInvalidPackages = isSelected
                    previewButtonStat(previewButton)
                }
            }.apply { 
                alignmentX = Component.LEFT_ALIGNMENT 
            })
            
            // 添加垂直间距
            add(Box.createVerticalStrut(5))
            
            // 平台特定二进制文件选项（第三行，仅在适用时显示）
            validForDependencyType({
                add(createFilterOption("Choose:", "Platform Binaries") {
                    isSelected = configOptions.showPlatformSpecificBinaries
                    toolTipText = "Show platform-specific binary files (win32-x64, darwin-arm64, etc.)"
                    addActionListener {
                        configOptions.showPlatformSpecificBinaries = isSelected
                        previewButtonStat(previewButton)
                    }
                }.apply { 
                    alignmentX = Component.LEFT_ALIGNMENT 
                })
                // 再添加一个垂直间距
                add(Box.createVerticalStrut(5))
            }, arrayOf(DependencyType.NPM, DependencyType.PIP))
            
            // 包名/组件输入框选项（最后一行）
            add(createPackageNameOption().apply { 
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
    }

    private fun createPackageNameOption(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {  // 使用FlowLayout但无内边距
            // 内部使用BoxLayout的面板
            val innerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(2, 5, 2, 0)  // 左边加5像素对齐
                
                add(JLabel("Choose:"))
                
                // 使用变量保存标签引用，便于后续控制可见性
                val groupArtifactLabel = JLabel("[Group:Artifact]:")
                val packageNameLabel = JLabel("[Package Name]:")
                
                validForDependencyType({
                    add(groupArtifactLabel)
                    packageNameLabel.isVisible = false
                }, arrayOf(DependencyType.MAVEN, DependencyType.GRADLE))
                
                validForDependencyType({
                    add(packageNameLabel)
                    groupArtifactLabel.isVisible = false
                }, arrayOf(DependencyType.NPM, DependencyType.PIP))
                
                add(JTextField().apply {
                    groupArtifactField = this  // 初始化输入框引用
                    
                    validForDependencyType({
                        toolTipText = "Format: groupId:artifactId or groupId"
                    }, arrayOf(DependencyType.MAVEN, DependencyType.GRADLE))
                    validForDependencyType({
                        toolTipText = "Package name (e.g., 'react' or 'lodash')"
                    }, arrayOf(DependencyType.NPM))
                    validForDependencyType({
                        toolTipText = "Package name (e.g., 'requests' or 'django')"
                    }, arrayOf(DependencyType.PIP))
                    
                    // 设置输入框的大小
                    columns = 15
                    preferredSize = Dimension(160, preferredSize.height)
                    
                    // 添加文档监听器以更新配置
                    document.addDocumentListener(object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateConfig()
                        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateConfig()
                        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateConfig()
                        
                        private fun updateConfig() {
                            ConfigOptions.getInstance().targetPackage = text.takeIf { it.isNotEmpty() } ?: ""
                            previewButtonStat(previewButton)
                        }
                    })
                })
                
                // 设置最大宽度以确保面板不会过宽
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
            
            // 添加内部面板
            add(innerPanel)
        }
    }

    // 添加处理Python环境类型变化的方法
    private fun updatePythonEnvironment(envType: PythonEnvironmentType) {
        if (currentDependencyType != DependencyType.PIP) return
        
        val pipConfigService = configService as? PIPConfigService ?: return
        
        // 将环境类型设置到PIPConfigService中
        pipConfigService.setEnvironmentType(envType)
        
        // 获取更新后的路径
        val newPath = pipConfigService.getLocalRepository(true)
        
        // 更新UI显示，无论路径是否有效
        currentRepoPath = newPath
        pathField.text = newPath
        
        // 检查是否需要用户选择Conda目录
        if (initialized.get() && envType == PythonEnvironmentType.CONDA && pipConfigService.isCondaSelectionNeeded()) {
            // 如果是Conda但需要用户选择，提示用户选择
            val promptForCondaPath = promptForCondaPath()
            if (promptForCondaPath != null) {
                currentRepoPath = pipConfigService.getLocalRepository(true)
                pathField.text = currentRepoPath
            } else {
                // 如果用户取消了选择，清空路径并更新UI
                pipConfigService.updateLocalRepository("")
                currentRepoPath = ""
                pathField.text = ""
            }

        } else if (newPath.isEmpty() || !File(newPath).exists()) {
            // 如果路径无效，清空文本框
            pathField.text = ""
            currentRepoPath = ""
        }
        
        // 更新按钮状态
        refreshButton.isEnabled = false
    }
    
    // 提示用户选择Conda安装路径
    private fun promptForCondaPath() : String? {
        // 显示提示对话框
        Messages.showInfoMessage(
            project,
            "Could not detect Conda installation directory automatically. Please select your Conda installation directory.",
            "Select Conda Directory"
        )
        
        // 显示文件选择对话框
        val fileChooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Select Conda Installation Directory"
        }
        
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val selectedDir = fileChooser.selectedFile
            val pipConfigService = configService as? PIPConfigService ?: return null

            // 让服务处理选择的目录
            pipConfigService.processSelectedCondaDirectory(selectedDir)
            val sitePkgsPath = configService.getLocalRepository(true)
            if (sitePkgsPath.isNotEmpty()) {
                return sitePkgsPath
            } else {
                Messages.showErrorDialog(
                    project,
                    "Could not find a valid site-packages directory in the selected installation. Please check your selection.",
                    "Invalid Directory"
                )
            }
        }
        return null
    }
}