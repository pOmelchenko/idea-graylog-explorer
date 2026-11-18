package dev.graylog.jetbrains;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.fields.ExtendableTextField;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.event.*;
import java.util.*;

/** A native, non-focus-stealing popup: typing stays in the search field. */
final class QueryCompletion implements Disposable {
    private final ExtendableTextField field;
    private final Timer delay;
    private final Set<String> fields = new TreeSet<>(QuerySuggestions.BUILT_INS);
    private final Map<String, List<String>> values = new HashMap<>();
    private JBPopup popup;
    private final JBList<QuerySuggestions.Item> list = new JBList<>();
    private boolean inserting, disposed;

    QueryCompletion(ExtendableTextField field) {
        this.field = field;
        delay = new Timer(180, e -> show(false)); delay.setRepeats(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setBorder(JBUI.Borders.empty(3, 0));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, i, selected, false); setBorder(JBUI.Borders.empty(3, 8)); return this;
            }
        });
        list.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) accept(); } });
        field.setFocusTraversalKeysEnabled(false);
        field.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(DocumentEvent e) { if (!inserting) { close(); delay.restart(); } }
        });
        field.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { delay.stop(); close(); } });
        field.addCaretListener(e -> { if (!inserting) close(); });
        field.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_SPACE) { show(true); e.consume(); return; }
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_ENTER) { close(); return; }
                if (popup != null && popup.isVisible()) switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> { list.setSelectedIndex(Math.min(list.getModel().getSize() - 1, list.getSelectedIndex() + 1)); list.ensureIndexIsVisible(list.getSelectedIndex()); e.consume(); }
                    case KeyEvent.VK_UP -> { list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1)); list.ensureIndexIsVisible(list.getSelectedIndex()); e.consume(); }
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> { accept(); e.consume(); }
                    case KeyEvent.VK_ESCAPE -> { close(); e.consume(); }
                    case KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_HOME, KeyEvent.VK_END -> close();
                }
                else if (e.getKeyCode() == KeyEvent.VK_TAB) { if (e.isShiftDown()) field.transferFocusBackward(); else field.transferFocus(); e.consume(); }
            }
        });
    }
    void reset(LogFormat format) {
        close(); fields.clear(); fields.addAll(QuerySuggestions.BUILT_INS); fields.addAll(format.aliases().values()); values.clear();
    }
    void observe(java.util.List<LogEvent> events, String serviceField) {
        for (LogEvent event : events) {
            fields.addAll(event.fields().keySet());
            for (String key : java.util.List.of(serviceField, "source", "application", "stage", "channel", "facility")) {
                String value = event.text(key);
                if (!value.isBlank()) {
                    java.util.List<String> known = values.computeIfAbsent(key, ignored -> new ArrayList<>());
                    if (known.size() < 100 && !known.contains(value)) { known.add(value); Collections.sort(known); }
                }
            }
        }
    }
    void show(boolean explicit) {
        delay.stop(); close();
        if (disposed || !field.isShowing() || !field.hasFocus()) return;
        if (field.getSelectionStart() != field.getSelectionEnd()) return;
        if (!explicit && (field.getText().isBlank() || field.getCaretPosition() == 0)) return;
        if (!explicit && Character.isWhitespace(field.getText().charAt(field.getCaretPosition() - 1))
                && !field.getText().substring(0, field.getCaretPosition()).stripTrailing().endsWith(":")) return;
        var suggestions = QuerySuggestions.at(field.getText(), field.getCaretPosition(), fields, values);
        if (suggestions.isEmpty()) return;
        list.setListData(suggestions.toArray(QuerySuggestions.Item[]::new)); list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(9, suggestions.size()));
        JComponent scroll = ExplorerUi.scroll(list); scroll.setPreferredSize(JBUI.size(Math.max(260, Math.min(430, field.getWidth())), Math.min(9, suggestions.size()) * 26 + 6));
        JBPopup next = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, list).setRequestFocus(false).setFocusable(false)
                .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true).createPopup();
        next.addListener(new com.intellij.openapi.ui.popup.JBPopupListener() {
            @Override public void onClosed(com.intellij.openapi.ui.popup.LightweightWindowEvent event) { if (popup == next) popup = null; }
        });
        popup = next; next.showUnderneathOf(field);
    }
    void accept() {
        QuerySuggestions.Item item = list.getSelectedValue(); if (item == null || popup == null) return;
        String text = field.getText(); inserting = true; close();
        try { field.setText(item.apply(text)); field.setCaretPosition(item.from() + item.insert().length()); }
        finally { inserting = false; }
        field.requestFocusInWindow();
    }
    private void close() { if (popup != null) { popup.cancel(); popup = null; } }
    com.intellij.openapi.ui.popup.JBPopup popup() { return popup; }
    @Override public void dispose() { disposed = true; delay.stop(); close(); }
}
