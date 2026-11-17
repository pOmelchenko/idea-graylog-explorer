package dev.graylog.jetbrains

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

/** Java-facing form adapter; IntelliJ UI DSL owns layout, label alignment and group spacing. */
class SettingsForms {
    private class Section(val title: String?, val rows: MutableList<Panel.() -> Unit> = mutableListOf())
    private val sections = mutableListOf(Section(null))
    fun add(label: String, input: JComponent) {
        sections.last().rows += {
            row(if (label.endsWith(':')) label else "$label:") {
                val control = cell(input)
                if (input !is JSpinner && input !is JComboBox<*>) control.align(AlignX.FILL).resizableColumn()
            }
        }
    }
    fun checkbox(input: JCheckBox) = component(input)
    fun comment(text: String) { sections.last().rows += { row { comment(text) } } }
    fun group(title: String) { sections += Section(title) }
    fun component(input: JComponent) {
        sections.last().rows += {
            row {
                val control = cell(input)
                if (input !is AbstractButton) control.align(AlignX.FILL).resizableColumn()
            }
        }
    }
    fun panel(): JPanel = panel {
        for (section in sections) {
            if (section.title == null) section.rows.forEach { it(this) }
            else group(section.title) { section.rows.forEach { it(this) } }
        }
    }

    companion object {
        @JvmStatic fun help(text: String): JLabel = JBLabel("<html>$text</html>").apply {
            font = JBFont.medium(); foreground = UIUtil.getContextHelpForeground()
        }
        @JvmStatic fun tab(content: JComponent): JPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(12); add(content)
        }
        @JvmStatic fun number(initial: Int, min: Int, max: Int): JSpinner = JSpinner(SpinnerNumberModel(initial, min, max, 1)).apply {
            (editor as JSpinner.DefaultEditor).textField.columns = 5
        }
        @JvmStatic fun number(field: JSpinner): Int = (field.value as Number).toInt()
        @JvmStatic fun <T> combo(): JComboBox<T> = combo(null)
        @JvmStatic fun <T> combo(values: Array<T>?): JComboBox<T> = object : ComboBox<T>() {
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                val width = if (font == null) 0 else (0 until itemCount).maxOfOrNull {
                    getFontMetrics(font).stringWidth(getItemAt(it).toString())
                } ?: 0
                return Dimension(maxOf(size.width, minOf(JBUI.scale(360), width + JBUI.scale(64))), size.height)
            }
        }.apply { values?.forEach { addItem(it) } }
        @JvmStatic fun color(field: JBTextField): JPanel {
            val picker = ColorPanel().apply { setSupportTransparency(false) }
            var syncing = false
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    if (syncing) return
                    try {
                        syncing = true
                        if (field.text.matches(Regex("#[0-9A-Fa-f]{6}"))) picker.selectedColor = Color.decode(field.text)
                    } finally { syncing = false }
                }
            })
            picker.addActionListener { if (!syncing) picker.selectedColor?.let { field.text = SourceColors.hex(it) } }
            return JPanel(BorderLayout()).apply { add(picker, BorderLayout.WEST) }
        }
    }
}
