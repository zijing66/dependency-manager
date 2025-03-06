package com.github.zijing66.dependencymanager.ui

import com.github.zijing66.dependencymanager.models.CleanupPreview
import com.github.zijing66.dependencymanager.models.CleanupSummary
import com.github.zijing66.dependencymanager.services.DependencyManagerDetector
import com.github.zijing66.dependencymanager.services.MavenConfigService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import javax.swing.*
import javax.swing.table.AbstractTableModel
import java.text.DecimalFormat
import java.util.Date
private val LOG = logger<DependencyCleanupToolWindow>()

class DependencyCleanupToolWindow : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        LOG.info("Creating tool window content")
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(
            DependencyCleanupPanel(project),
            "",
            false
        )
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

private class DependencyCleanupPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val previewTable: JBTable
    private val previewModel: PreviewTableModel
    private val progressBar: JProgressBar
    private val statusLabel: JLabel
    private var currentPreview: CleanupSummary? = null
    
    init {
        // Initialize components
        previewModel = PreviewTableModel()
        previewTable = JBTable(previewModel)
        progressBar = JProgressBar()
        statusLabel = JLabel()
        
        setupUI()
    }
    
    private fun setupUI() {
        // Top panel with controls
        val controlPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(5)
        }
        
        val detector = DependencyManagerDetector()
        val dependencyType = detector.detectDependencyType(project)
        
        controlPanel.add(JLabel("Detected dependency manager: $dependencyType"))
        
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        
        val previewButton = JButton("Preview Cleanup").apply {
            addActionListener { loadPreview() }
        }
        
        val cleanButton = JButton("Clean Selected").apply {
            isEnabled = false
            addActionListener { performCleanup() }
        }
        
        buttonPanel.add(previewButton)
        buttonPanel.add(Box.createHorizontalStrut(10))
        buttonPanel.add(cleanButton)
        
        controlPanel.add(buttonPanel)
        controlPanel.add(Box.createVerticalStrut(10))
        controlPanel.add(progressBar)
        controlPanel.add(Box.createVerticalStrut(5))
        controlPanel.add(statusLabel)
        
        add(controlPanel, BorderLayout.NORTH)
        
        // Table panel
        val tablePanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 5, 5, 5)
        }
        
        setupTable()
        tablePanel.add(JBScrollPane(previewTable), BorderLayout.CENTER)
        
        add(tablePanel, BorderLayout.CENTER)
        
        // Initialize progress bar
        progressBar.isVisible = false
    }
    
    private fun setupTable() {
        previewTable.apply {
            setShowGrid(false)
            intercellSpacing = java.awt.Dimension(0, 0)
            autoCreateRowSorter = true
            
            columnModel.getColumn(0).preferredWidth = 30 // Checkbox column
            columnModel.getColumn(1).preferredWidth = 200 // Package name
            columnModel.getColumn(2).preferredWidth = 100 // Size
            columnModel.getColumn(3).preferredWidth = 150 // Last modified
        }
    }
    
    private fun loadPreview() {
        progressBar.isVisible = true
        progressBar.isIndeterminate = true
        statusLabel.text = "Loading preview..."
        
        SwingUtilities.invokeLater {
            when (val service = MavenConfigService()) {
                is MavenConfigService -> {
                    currentPreview = service.previewCleanup()
                    previewModel.setData(currentPreview?.previewItems ?: emptyList())
                    updateStatus()
                }
            }
            
            progressBar.isVisible = false
            previewTable.parent.parent.isVisible = true
        }
    }
    
    private fun performCleanup() {
        val selectedItems = previewModel.getSelectedItems()
        if (selectedItems.isEmpty()) return
        
        progressBar.isVisible = true
        progressBar.isIndeterminate = false
        progressBar.value = 0
        
        val service = MavenConfigService()
        service.cleanupFailedDownloads(
            project = project,
            selectedItems = selectedItems,
            onProgress = { current, total ->
                progressBar.value = (current.toFloat() / total * 100).toInt()
                statusLabel.text = "Cleaning up: $current/$total files"
            },
            onComplete = { results ->
                SwingUtilities.invokeLater {
                    progressBar.isVisible = false
                    val success = results.count { it.success }
                    statusLabel.text = "Cleanup complete: $success/${results.size} files cleaned"
                    loadPreview() // Refresh the preview
                }
            }
        )
    }
    
    private fun updateStatus() {
        currentPreview?.let { preview ->
            val totalSize = formatFileSize(preview.totalSize)
            statusLabel.text = "Found ${preview.totalFiles} files ($totalSize)"
        }
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
}

private class PreviewTableModel : AbstractTableModel() {
    private val columns = arrayOf("", "Package Name", "Size", "Last Modified")
    private var data: List<CleanupPreview> = emptyList()
    
    fun setData(newData: List<CleanupPreview>) {
        data = newData
        fireTableDataChanged()
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
    
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = data[rowIndex]
        return when (columnIndex) {
            0 -> item.selected
            1 -> item.packageName
            2 -> formatFileSize(item.fileSize)
            3 -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(Date(item.lastModified))
            else -> ""
        }
    }
    
    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0 && value is Boolean) {
            data[rowIndex].selected = value
            fireTableCellUpdated(rowIndex, columnIndex)
        }
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
} 