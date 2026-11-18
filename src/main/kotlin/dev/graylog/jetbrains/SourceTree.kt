package dev.graylog.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import java.awt.*
import java.util.*
import java.util.concurrent.*
import java.util.function.*
import java.util.function.Supplier
import javax.swing.*
import javax.swing.tree.*

/** Source facets belong to the active connection; checking a facet keeps the active stream.  */
object SourceTree {
    @JvmStatic fun group(
        scope: String, summary: String, values: List<GraylogApi.SourceValue>,
        selected: Set<String>, needle: String, showAll: Boolean
    ): DefaultMutableTreeNode {
        val group = DefaultMutableTreeNode(Group(scope, summary))
        val sorted: MutableMap<String, GraylogApi.SourceValue> =
            TreeMap(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
        values.forEach({ value -> sorted.put(value.name(), value) })
        selected.forEach({ name -> sorted.putIfAbsent(name, GraylogApi.SourceValue(name, -1)) })
        if (showAll || "all sources".contains(needle)) group.add(DefaultMutableTreeNode(Entry(null, -1)))
        sorted.values.stream()
            .filter({ value -> showAll || value.name().lowercase(Locale.ROOT).contains(needle) })
            .forEach({ value -> group.add(DefaultMutableTreeNode(Entry(value.name(), value.count()))) })
        if (showAll) group.add(DefaultMutableTreeNode(Status(summary)))
        return group
    }

    /** Keep the platform's row selection painting; only source rows gain a checkbox.  */
    @JvmStatic fun renderer(
        nativeRenderer: TreeCellRenderer, selected: Supplier<Set<String>>, options: Supplier<IdeOptions>,
        enabled: Supplier<Boolean>
    ): TreeCellRenderer {
        return object : TreeCellRenderer {
            val row = JPanel(BorderLayout(JBUI.scale(4), 0))
            val check = JBCheckBox()
            val label: ColoredTreeCellRenderer = object : ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(
                    tree: JTree,
                    value: Any,
                    chosen: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    index: Int,
                    focus: Boolean
                ) {
                    val user: Any = (value as DefaultMutableTreeNode).getUserObject()
                    if (user is Entry) {
                        append(user.toString())
                        if (user.name != null) setIcon(
                            ExplorerUi.colorIcon(SourceColors.color(user.name, options.get().sourceColors)
                            )
                        )
                        if (user.count >= 0) append(
                            "  " + String.format(Locale.ROOT, "%,d", user.count),
                            SimpleTextAttributes.GRAYED_ATTRIBUTES
                        )
                    }
                    setToolTipText(ExplorerUi.plainTooltip(user.toString()))
                    setOpaque(false)
                    setIconOpaque(false)
                }
            }
            val groupLabel: ColoredTreeCellRenderer = object : ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(
                    tree: JTree,
                    value: Any,
                    chosen: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    index: Int,
                    focus: Boolean
                ) {
                    val user: Any = (value as DefaultMutableTreeNode).getUserObject()
                    if (user is Group) {
                        setIcon(AllIcons.Nodes.Folder)
                        append("Sources")
                        append("  " + user.scope, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        setToolTipText(user.summary)
                    } else {
                        setIcon(null)
                        append(user.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        setToolTipText(user.toString())
                    }
                    setOpaque(false)
                    setIconOpaque(false)
                }
            }

            init {
                row.setOpaque(false)
                check.setOpaque(false)
                row.add(check, BorderLayout.WEST)
                row.add(label)
            }

            override fun getTreeCellRendererComponent(
                tree: JTree,
                value: Any,
                chosen: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                index: Int,
                focus: Boolean
            ): Component? {
                val user: Any? = (value as DefaultMutableTreeNode).getUserObject()
                if (user is Group || user is Status) return groupLabel.getTreeCellRendererComponent(
                    tree,
                    value,
                    chosen,
                    expanded,
                    leaf,
                    index,
                    focus
                )
                if (user !is Entry) return nativeRenderer.getTreeCellRendererComponent(
                    tree,
                    value,
                    chosen,
                    expanded,
                    leaf,
                    index,
                    focus
                )
                check.setSelected(if (user.name == null) selected.get().isEmpty() else selected.get().contains(user.name))
                check.setEnabled(enabled.get())
                row.setToolTipText(
                    ExplorerUi.plainTooltip(
                        user.toString().toString() + (if (user.count < 0) "" else " · " + user.count + " messages")
                    )
                )
                label.getTreeCellRendererComponent(tree, value, chosen, expanded, leaf, index, focus)
                return row
            }
        }
    }

    @JvmRecord
    data class Entry(val name: String?, val count: Long) {
        override fun toString(): String = name ?: "All sources"
    }

    @JvmRecord
    data class Status(val text: String) {
        override fun toString(): String = text
    }

    @JvmRecord
    data class Group(val scope: String, val summary: String) {
        override fun toString(): String = "Sources"
    }
}
