package dev.graylog.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.util.concurrent.*
import java.util.function.*
import java.util.function.BooleanSupplier
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer

/** Shared native tool-window surfaces and adapters for the Explorer's command models.  */
object ExplorerUi {
    @JvmField val ERROR_COLOR: Color = JBColor(Color(175, 41, 45), Color(255, 113, 119))
    @JvmField val WARN_COLOR: Color = JBColor(Color(145, 99, 10), Color(231, 181, 73))
    @JvmStatic fun colorIcon(color: Color?): Icon {
        return object : Icon {
            override fun getIconWidth(): Int = JBUI.scale(10)

            override fun getIconHeight(): Int = JBUI.scale(10)

            override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
                g.setColor(color)
                g.fillRoundRect(x + 1, y + 1, JBUI.scale(8), JBUI.scale(8), JBUI.scale(2), JBUI.scale(2))
            }
        }
    }

    @JvmStatic fun scroll(content: JComponent): JBScrollPane {
        val pane = JBScrollPane(content)
        pane.setBorder(JBUI.Borders.empty())
        pane.setViewportBorder(JBUI.Borders.empty())
        pane.getViewport().setBackground(content.getBackground())
        return pane
    }

    @JvmStatic fun bar(main: JComponent?, right: JComponent?, topSeparator: Boolean): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.setBorder(
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), if (topSeparator) 1 else 0, 0, if (topSeparator) 0 else 1, 0),
                JBUI.Borders.empty(3, 8)
            )
        )
        if (main != null) panel.add(main, BorderLayout.CENTER)
        if (right != null) panel.add(right, BorderLayout.EAST)
        return panel
    }

    @JvmStatic fun toolbar(place: String, target: JComponent, vararg actions: AnAction?): JComponent = toolbar(place, target, true, *actions)

    @JvmStatic fun toolbar(place: String, target: JComponent, horizontal: Boolean, vararg actions: AnAction?): JComponent {
        val toolbar: ActionToolbar =
            ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(*actions), horizontal)
        toolbar.setTargetComponent(target)
        val component: JComponent = toolbar.getComponent()
        if (horizontal) {
            component.setOpaque(false)
            component.setBorder(JBUI.Borders.empty())
        }
        return component
    }

    @JvmStatic fun command(title: String, icon: Icon, command: Runnable, enabled: BooleanSupplier): AnAction {
        return object : DumbAwareAction(title, title, icon) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.getPresentation().setEnabled(enabled.getAsBoolean())
            }

            override fun actionPerformed(event: AnActionEvent) {
                if (enabled.getAsBoolean()) command.run()
            }
        }
    }

    @JvmStatic fun button(model: AbstractButton, icon: Icon, text: Boolean): AnAction {
        return button(model, icon, text, BooleanSupplier { true })
    }

    @JvmStatic fun button(model: AbstractButton, icon: Icon, text: Boolean, visible: BooleanSupplier): AnAction {
        val action: AnAction = object : DumbAwareAction(model.getText(), model.getText(), icon) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.getPresentation().setText(model.getText())
                event.getPresentation()
                    .setDescription(if (model.getToolTipText() == null) model.getText() else model.getToolTipText())
                event.getPresentation().setEnabled(model.isEnabled())
                event.getPresentation().setVisible(visible.getAsBoolean())
            }

            override fun actionPerformed(event: AnActionEvent) {
                if (model.isEnabled()) model.doClick(0)
            }
        }
        action.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, text)
        return action
    }

    @JvmStatic fun toggle(model: JToggleButton, icon: Icon): AnAction = toggle(model, icon, true)

    @JvmStatic fun toggle(model: JToggleButton, icon: Icon, text: Boolean): AnAction {
        val action: AnAction = object : DumbAwareToggleAction(model.getText(), model.getToolTipText(), icon) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun isSelected(event: AnActionEvent): Boolean = model.isSelected()

            override fun setSelected(event: AnActionEvent, selected: Boolean) {
                if (model.isSelected() != selected) model.doClick(0)
            }

            override fun update(event: AnActionEvent) {
                super.update(event)
                event.getPresentation().setText(model.getText())
                event.getPresentation()
                    .setDescription(if (model.getToolTipText() == null) model.getText() else model.getText() + " — " + model.getToolTipText())
            }
        }
        action.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, text)
        return action
    }

    @JvmStatic fun filterField(hint: String, search: Runnable?): ExtendableTextField {
        val field = ExtendableTextField()
        field.getEmptyText().setText(hint)
        field.addExtension(object : ExtendableTextComponent.Extension {
            override fun getIcon(hovered: Boolean): Icon = AllIcons.Actions.Search

            override fun isIconBeforeText(): Boolean = true

            override fun getTooltip(): String = if (search == null) hint else "Run search (Enter)"

            override fun getActionOnClick(): Runnable = search ?: Runnable { field.requestFocusInWindow() }
        })
        field.addExtension(object : ExtendableTextComponent.Extension {
            override fun getIcon(hovered: Boolean): Icon? = if (field.getText().isEmpty()) null else AllIcons.Actions.Close

            override fun getTooltip(): String = "Clear"

            override fun getActionOnClick(): Runnable = Runnable {
                    field.setText("")
                    field.requestFocusInWindow()
                }
        })
        field.getDocument().addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                field.revalidate()
                field.repaint()
            }
        })
        return field
    }

    @JvmStatic fun choice(title: String, prefix: String, model: JComboBox<*>): AnAction = ChoiceAction(title, prefix, model)

    /** Log data is plain text even when it starts with Swing's HTML marker.  */
    @JvmStatic fun plainTooltip(value: String?): String? {
        if (value == null) return null
        val text = if (value.codePointCount(0, value.length) <= 500) value else value.substring(
            0,
            value.offsetByCodePoints(0, 500)
        ) + "…"
        return if (javax.swing.plaf.basic.BasicHTML.isHTMLString(text))
            "<html>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text) + "</html>"
        else
            text
    }

    @JvmStatic fun cells(): DefaultTableCellRenderer {
        return object : PlainCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any,
                selected: Boolean,
                focus: Boolean,
                row: Int,
                column: Int
            ): Component {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column)
                setBorder(JBUI.Borders.empty(0, 8))
                val tooltip = if (value == null) null else value.toString()
                setToolTipText(plainTooltip(tooltip))
                return this
            }
        }
    }

    private class ChoiceAction(title: String, prefix: String, model: JComboBox<*>) : ComboBoxAction(),
        DumbAware {
        private val model: JComboBox<*>
        private val prefix: String

        init {
            this.model = model
            this.prefix = prefix
            getTemplatePresentation().setText(title)
            getTemplatePresentation().setDescription(title)
            setSmallVariant(true)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.getPresentation().setText(prefix + model.getSelectedItem())
            event.getPresentation().setEnabled(model.isEnabled())
        }

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            for (index in 0..<model.getItemCount()) {
                val value = index
                group.add(object : DumbAwareToggleAction(model.getItemAt(index).toString()) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                    override fun isSelected(event: AnActionEvent): Boolean = model.getSelectedIndex() == value

                    override fun setSelected(event: AnActionEvent, selected: Boolean) {
                        if (selected) model.setSelectedIndex(value)
                    }
                })
            }
            return group
        }
    }

    open class PlainCellRenderer : DefaultTableCellRenderer() {
        init {
            putClientProperty("html.disable", true)
        }
    }
}
