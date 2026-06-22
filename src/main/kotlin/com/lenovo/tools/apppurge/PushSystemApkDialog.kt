package com.lenovo.tools.apppurge

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ComboboxWithBrowseButton
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.JTextField
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val PUSH_DIALOG_MIN_FIELD_WIDTH = 430
private const val PUSH_DIALOG_MAX_FIELD_WIDTH = 460
private const val PUSH_DIALOG_CONTENT_WIDTH = 560
private const val PUSH_DIALOG_CONTENT_HEIGHT = 220
private const val PUSH_DIALOG_FIELD_HEIGHT = 32

internal class PushSystemApkDialog(
    private val project: Project,
    private val projectBasePath: String?,
    private val adbPath: String,
    private val serial: String,
    private val info: AppInstallInfo,
    private val target: SystemApkTarget,
) : DialogWrapper(project, true) {
    private val apkChoices = info.apkFiles
    private var selectedApk: File? = apkChoices.firstOrNull()
    private lateinit var apkCombo: ComboBox<String>
    private val validationClosed = AtomicBoolean(false)
    private val validationGeneration = AtomicInteger(0)
    private val validationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppPurge-ApkValidation").apply { isDaemon = true }
    }
    private var validationFuture: Future<*>? = null
    private val validationSpinner = AnimatedIcon.Default()
    private val packageValidationField = JTextField("Select an APK to validate").apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.emptyRight(12)
        horizontalAlignment = JTextField.RIGHT
        foreground = UIManager.getColor("Label.disabledForeground")
        toolTipText = "APK package validation status"
    }
    private val validationTimer = Timer(400) { validateSelectedApk(showError = false, closeOnSuccess = false) }.apply {
        isRepeats = false
    }
    private val fieldWidth = preferredPushFieldWidth()
    private val targetField = JTextField(target.targetPath).apply {
        preferredSize = Dimension(fieldWidth, PUSH_DIALOG_FIELD_HEIGHT)
        caretPosition = 0
    }
    private val removeOverlayAllowed = info.status == InstallStatus.UPDATED_SYSTEM_APP && target.hasDataOverlay
    private val clearDataAllowed = info.isInstalled
    private val removeOverlayCheck = JCheckBox("Remove /data/app overlay", removeOverlayAllowed).apply {
        isEnabled = removeOverlayAllowed
        toolTipText = if (removeOverlayAllowed) {
            "Remove the user-installed update so the pushed system APK can take effect after reboot"
        } else {
            "No updated-system-app overlay detected for this package"
        }
    }
    private val clearDataCheck = JCheckBox("Clear app data", clearDataAllowed).apply {
        isEnabled = clearDataAllowed
        toolTipText = if (clearDataAllowed) {
            "Clear current installed app data after pushing"
        } else {
            "Package is not installed on the current device user"
        }
    }

    init {
        title = "Push System APK"
        setOKButtonText("Push")
        init()
        SwingUtilities.invokeLater {
            validateSelectedApk(showError = false, closeOnSuccess = false)
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
        }
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(7, 6, 7, 6)
        }

        fun addLabel(text: String) {
            gbc.gridx = 0
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            panel.add(JLabel(text), gbc)
        }

        fun addField(component: JComponent) {
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.anchor = GridBagConstraints.WEST
            panel.add(component, gbc)
            gbc.gridy++
        }

        addLabel("Package:")
        addField(JTextField(info.packageName).apply {
            isEditable = false
            preferredSize = Dimension(fieldWidth, PUSH_DIALOG_FIELD_HEIGHT)
        })

        addLabel("Local APK:")
        addField(localApkComponent())

        addLabel("Device Target:")
        addField(deviceTargetComponent())
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.WEST
        panel.add(deviceTargetSourceLabel(), gbc)
        gbc.gridy++

        gbc.gridx = 1
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        panel.add(JPanel(GridLayout(0, 1, 0, 2)).apply {
            border = JBUI.Borders.emptyTop(4)
            add(removeOverlayCheck)
            add(clearDataCheck)
        }, gbc)

        return panel.apply {
            preferredSize = Dimension(PUSH_DIALOG_CONTENT_WIDTH, PUSH_DIALOG_CONTENT_HEIGHT)
        }
    }

    private fun localApkComponent(): JComponent {
        apkCombo = ComboBox(apkChoices.map { relativeApkPath(it) }.toTypedArray()).apply {
            isEditable = true
            if (apkChoices.isNotEmpty()) selectedIndex = 0
            toolTipText = selectedApk?.absolutePath
            addActionListener {
                val idx = selectedIndex
                if (idx >= 0 && idx < apkChoices.size) {
                    selectedApk = apkChoices[idx]
                    toolTipText = selectedApk?.absolutePath
                }
                schedulePackageValidation()
            }
        }
        (apkCombo.editor.editorComponent as? JTextField)?.document?.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = schedulePackageValidation()
            override fun removeUpdate(e: DocumentEvent) = schedulePackageValidation()
            override fun changedUpdate(e: DocumentEvent) = schedulePackageValidation()
        })
        return ComboboxWithBrowseButton(apkCombo).apply {
            preferredSize = Dimension(fieldWidth, PUSH_DIALOG_FIELD_HEIGHT)
            toolTipText = "Choose APK"
            addActionListener { chooseLocalApk() }
        }
    }

    private fun preferredPushFieldWidth(): Int {
        val fontMetrics = JLabel().getFontMetrics(UIManager.getFont("TextField.font") ?: UIManager.getFont("Label.font"))
        val longest = listOf(
            info.packageName,
            target.targetPath,
        ) + apkChoices.map { relativeApkPath(it) }
        val contentWidth = (longest.maxOfOrNull { fontMetrics.stringWidth(it) } ?: PUSH_DIALOG_MIN_FIELD_WIDTH) + 80
        return contentWidth.coerceIn(PUSH_DIALOG_MIN_FIELD_WIDTH, PUSH_DIALOG_MAX_FIELD_WIDTH)
    }

    private fun chooseLocalApk() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("apk")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        selectedApk = File(chosen.path)
        apkCombo.selectedItem = relativeApkPath(selectedApk!!)
        apkCombo.toolTipText = selectedApk?.absolutePath
        schedulePackageValidation()
    }

    private fun resolveSelectedApk(): File? {
        val current = apkCombo.editor?.item?.toString()?.trim().orEmpty()
        if (current.isNotBlank()) {
            val typed = File(current)
            selectedApk = if (typed.isAbsolute) typed else projectBasePath?.let { File(it, current) } ?: typed
        }
        return selectedApk
    }

    private fun deviceTargetComponent(): JComponent {
        targetField.toolTipText = target.detectedPath?.let { "Detected: $it" } ?: "Default path"
        return targetField
    }

    private fun deviceTargetSourceLabel(): JComponent {
        val text = if (target.detectedPath != null) {
            "Target Path: detected original system path"
        } else {
            "Target Path: generated default path"
        }
        return JLabel(text).apply {
            foreground = UIManager.getColor("Label.foreground")
            font = font.deriveFont(font.size - 1f)
            border = JBUI.Borders.empty(0, 2, 4, 0)
            toolTipText = target.detectedPath?.let { "Detected: $it" } ?: "Generated from module name, not detected"
        }
    }

    override fun doOKAction() {
        val apk = resolveSelectedApk()
        if (apk == null || !apk.isFile) {
            Messages.showErrorDialog("Choose a valid local APK file.", "AppPurge")
            return
        }
        if (!targetField.text.trim().endsWith(".apk", ignoreCase = true)) {
            Messages.showErrorDialog("Device Target must be a full .apk path.", "AppPurge")
            return
        }
        validateSelectedApk(showError = true, closeOnSuccess = true)
    }

    override fun createSouthPanel(): JComponent {
        val actions = super.createSouthPanel()
        return JPanel(BorderLayout()).apply {
            add(packageValidationField, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
    }

    override fun doCancelAction() {
        stopPackageValidation()
        super.doCancelAction()
    }

    override fun dispose() {
        stopPackageValidation()
        super.dispose()
    }

    private fun schedulePackageValidation() {
        if (!this::apkCombo.isInitialized) return
        validationGeneration.incrementAndGet()
        validationFuture?.cancel(true)
        packageValidationField.text = "APK package validation pending"
        packageValidationField.foreground = UIManager.getColor("Label.disabledForeground")
        packageValidationField.toolTipText = "Waiting to validate the selected APK"
        showValidationButton()
        validationTimer.restart()
    }

    private fun validateSelectedApk(showError: Boolean, closeOnSuccess: Boolean) {
        validationTimer.stop()
        val apk = resolveSelectedApk()
        if (apk == null || !apk.isFile) {
            validationGeneration.incrementAndGet()
            showPackageValidationFailure("Invalid APK file")
            if (showError) Messages.showErrorDialog("Choose a valid local APK file.", "AppPurge")
            return
        }

        val generation = validationGeneration.incrementAndGet()
        packageValidationField.text = "Validating APK package..."
        packageValidationField.foreground = UIManager.getColor("Label.disabledForeground")
        packageValidationField.toolTipText = "Reading the APK manifest"
        showValidationButton()

        validationFuture?.cancel(true)
        validationFuture = validationExecutor.submit {
            val result = ApkInspector.inspectPackageName(apk, projectBasePath, adbPath)
            SwingUtilities.invokeLater {
                if (validationClosed.get() || generation != validationGeneration.get()) return@invokeLater
                when {
                    result.success && result.packageName == info.packageName -> {
                        packageValidationField.text = "APK package verified"
                        packageValidationField.foreground = successColor()
                        packageValidationField.toolTipText = "APK package matches ${info.packageName}"
                        showPushButton()
                        if (closeOnSuccess) finishOkAction()
                    }
                    result.success -> {
                        val actual = result.packageName.orEmpty()
                        showPackageValidationFailure(
                            "Package mismatch · Actual: $actual",
                            "Expected: ${info.packageName}\nActual: $actual\nSelect the text and press Ctrl+C to copy.",
                        )
                        if (showError) {
                            Messages.showErrorDialog(
                                """
                                APK package name does not match the selected module.

                                Expected: ${info.packageName}
                                Actual:   $actual
                                APK:      ${apk.absolutePath}
                                """.trimIndent(),
                                "AppPurge Package Mismatch",
                            )
                        }
                    }
                    else -> {
                        val error = result.error.orEmpty()
                        val shortError = error.lineSequence().firstOrNull(String::isNotBlank).orEmpty()
                        showPackageValidationFailure(
                            "Unable to read APK package${shortError.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                            error,
                        )
                        if (showError) {
                            Messages.showErrorDialog(
                                "Unable to read the APK package name.\n\nAPK: ${apk.absolutePath}\n\n${result.error.orEmpty()}",
                                "AppPurge APK Validation Failed",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showPackageValidationFailure(text: String, details: String = text) {
        packageValidationField.text = text
        packageValidationField.caretPosition = 0
        packageValidationField.foreground = errorColor()
        packageValidationField.toolTipText = details
        showDisabledPushButton(details)
    }

    private fun showValidationButton() {
        setOKActionEnabled(false)
        getButton(okAction)?.apply {
            text = ""
            icon = validationSpinner
            disabledIcon = validationSpinner
            toolTipText = "Validating APK package name"
        }
    }

    private fun showPushButton() {
        getButton(okAction)?.apply {
            icon = null
            disabledIcon = null
            text = "Push"
            toolTipText = null
        }
        setOKActionEnabled(true)
    }

    private fun showDisabledPushButton(reason: String) {
        getButton(okAction)?.apply {
            icon = null
            disabledIcon = null
            text = "Push"
            toolTipText = reason
        }
        setOKActionEnabled(false)
    }

    private fun stopPackageValidation() {
        if (!validationClosed.compareAndSet(false, true)) return
        validationTimer.stop()
        validationGeneration.incrementAndGet()
        validationFuture?.cancel(true)
        validationExecutor.shutdownNow()
    }

    private fun finishOkAction() {
        super.doOKAction()
    }

    private fun successColor(): Color =
        UIManager.getColor("Component.successFocusColor") ?: Color(0x59, 0xA8, 0x69)

    private fun errorColor(): Color =
        UIManager.getColor("Component.errorFocusColor") ?: Color(0xDB, 0x58, 0x60)

    fun request(): SystemPushRequest =
        SystemPushRequest(
            serial = serial,
            packageName = info.packageName,
            localApk = resolveSelectedApk()!!,
            targetPath = targetField.text.trim(),
            removeDataOverlay = removeOverlayCheck.isEnabled && removeOverlayCheck.isSelected,
            clearData = clearDataCheck.isEnabled && clearDataCheck.isSelected,
            adb = adbPath,
        )

    private fun relativeApkPath(apk: File): String {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return apk.absolutePath
        return runCatching {
            File(basePath).canonicalFile.toPath()
                .relativize(apk.canonicalFile.toPath())
                .toString()
                .replace(File.separatorChar, '/')
        }.getOrDefault(apk.absolutePath)
    }
}
