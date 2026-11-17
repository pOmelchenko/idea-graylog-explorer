package dev.graylog.jetbrains;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/** Source facets belong to the active connection; checking a facet keeps the active stream. */
final class SourceTree {
    record Entry(String name, long count) { @Override public String toString() { return name == null ? "All sources" : name; } }
    record Status(String text) { @Override public String toString() { return text; } }
    record Group(String scope, String summary) { @Override public String toString() { return "Sources"; } }

    static DefaultMutableTreeNode group(String scope, String summary, List<GraylogApi.SourceValue> values,
                                         Set<String> selected, String needle, boolean showAll) {
        var group = new DefaultMutableTreeNode(new Group(scope, summary));
        Map<String, GraylogApi.SourceValue> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        values.forEach(value -> sorted.put(value.name(), value));
        selected.forEach(name -> sorted.putIfAbsent(name, new GraylogApi.SourceValue(name, -1)));
        if (showAll || "all sources".contains(needle)) group.add(new DefaultMutableTreeNode(new Entry(null, -1)));
        sorted.values().stream().filter(value -> showAll || value.name().toLowerCase(Locale.ROOT).contains(needle))
                .forEach(value -> group.add(new DefaultMutableTreeNode(new Entry(value.name(), value.count()))));
        if (showAll) group.add(new DefaultMutableTreeNode(new Status(summary)));
        return group;
    }

    /** Keep the platform's row selection painting; only source rows gain a checkbox. */
    static TreeCellRenderer renderer(TreeCellRenderer nativeRenderer, Supplier<Set<String>> selected, Supplier<IdeOptions> options,
                                      Supplier<Boolean> enabled) {
        return new TreeCellRenderer() {
            final JPanel row = new JPanel(new BorderLayout(JBUI.scale(4), 0));
            final JBCheckBox check = new JBCheckBox();
            final ColoredTreeCellRenderer label = new ColoredTreeCellRenderer() {
                @Override public void customizeCellRenderer(JTree tree, Object value, boolean chosen, boolean expanded, boolean leaf, int index, boolean focus) {
                    Object user = ((DefaultMutableTreeNode) value).getUserObject();
                    if (user instanceof Entry entry) {
                        append(entry.toString());
                        if (entry.name() != null) setIcon(ExplorerUi.colorIcon(SourceColors.color(entry.name(), options.get().sourceColors)));
                        if (entry.count() >= 0) append("  " + String.format(Locale.ROOT, "%,d", entry.count()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                    setToolTipText(ExplorerUi.plainTooltip(user.toString()));
                    setOpaque(false); setIconOpaque(false);
                }
            };
            final ColoredTreeCellRenderer groupLabel = new ColoredTreeCellRenderer() {
                @Override public void customizeCellRenderer(JTree tree, Object value, boolean chosen, boolean expanded, boolean leaf, int index, boolean focus) {
                    Object user = ((DefaultMutableTreeNode) value).getUserObject();
                    if (user instanceof Group group) { setIcon(AllIcons.Nodes.Folder); append("Sources"); append("  " + group.scope(), SimpleTextAttributes.GRAYED_ATTRIBUTES); setToolTipText(group.summary()); }
                    else { setIcon(null); append(user.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES); setToolTipText(user.toString()); }
                    setOpaque(false); setIconOpaque(false);
                }
            };
            { row.setOpaque(false); check.setOpaque(false); row.add(check, BorderLayout.WEST); row.add(label); }
            @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean chosen, boolean expanded, boolean leaf, int index, boolean focus) {
                Object user = ((DefaultMutableTreeNode) value).getUserObject();
                if (user instanceof Group || user instanceof Status) return groupLabel.getTreeCellRendererComponent(tree, value, chosen, expanded, leaf, index, focus);
                if (!(user instanceof Entry entry)) return nativeRenderer.getTreeCellRendererComponent(tree, value, chosen, expanded, leaf, index, focus);
                check.setSelected(entry.name() == null ? selected.get().isEmpty() : selected.get().contains(entry.name()));
                check.setEnabled(enabled.get()); row.setToolTipText(ExplorerUi.plainTooltip(entry.toString() + (entry.count() < 0 ? "" : " · " + entry.count() + " messages")));
                label.getTreeCellRendererComponent(tree, value, chosen, expanded, leaf, index, focus);
                return row;
            }
        };
    }
}
