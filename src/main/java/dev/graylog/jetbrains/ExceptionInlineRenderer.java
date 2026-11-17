package dev.graylog.jetbrains;

import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

final class ExceptionInlineRenderer implements EditorCustomElementRenderer {
    private final String text;
    private final boolean selected;
    ExceptionInlineRenderer(String text, boolean selected) { this.text = text; this.selected = selected; }
    String text() { return text; }
    private Font font(Inlay<?> inlay) { return inlay.getEditor().getColorsScheme().getFont(EditorFontType.ITALIC); }
    @Override public int calcWidthInPixels(Inlay inlay) {
        return inlay.getEditor().getContentComponent().getFontMetrics(font(inlay)).stringWidth(text) + JBUI.scale(12);
    }
    @Override public void paint(Inlay inlay, Graphics2D graphics, Rectangle2D region, TextAttributes attributes) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setFont(font(inlay));
            copy.setColor(selected ? new JBColor(new Color(155, 66, 34), new Color(242, 164, 121))
                    : new JBColor(new Color(100, 100, 100), new Color(164, 164, 164)));
            var metrics = copy.getFontMetrics();
            float baseline = (float) (region.getY() + (region.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            copy.drawString(text, (float) region.getX() + JBUI.scale(8), baseline);
        } finally { copy.dispose(); }
    }
}
