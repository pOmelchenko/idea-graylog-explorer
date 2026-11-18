package dev.graylog.jetbrains;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;

final class SourceBandsTests {
    private static int assertions;
    static int run() {
        var rows = List.of("api", "api", "api", "worker", "api", "", "api");
        eq(new SourceBands.Segment(false, true), SourceBands.at(0, rows.size(), rows::get));
        eq(new SourceBands.Segment(true, true), SourceBands.at(1, rows.size(), rows::get));
        eq(new SourceBands.Segment(true, false), SourceBands.at(2, rows.size(), rows::get));
        eq(new SourceBands.Segment(false, false), SourceBands.at(3, rows.size(), rows::get));
        eq(null, SourceBands.at(5, rows.size(), rows::get)); eq(null, SourceBands.at(-1, rows.size(), rows::get));
        // View order, including a row filter, determines adjacency; original model indexes do not.
        var view = List.of("api", "worker", "api");
        eq(new SourceBands.Segment(false, false), SourceBands.at(0, view.size(), view::get));
        view = List.of("api", "api"); var visible = view;
        eq(new SourceBands.Segment(false, true), SourceBands.at(0, visible.size(), visible::get));
        // Distinct sources sharing a configured color must still have separate endpoints.
        var sameColor = List.of("api", "worker"); eq(new SourceBands.Segment(false, false), SourceBands.at(0, 2, sameColor::get));
        var image = new BufferedImage(12, 72, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 12, 72);
        for (int row = 0; row < 3; row++) {
            var cell = g.create(0, row * 24, 12, 24);
            SourceBands.paint(cell, Color.ORANGE, 2, 4, 24, 1, 3, SourceBands.at(row, 3, i -> "api")); cell.dispose();
        }
        g.dispose(); eq(Color.WHITE.getRGB(), image.getRGB(4, 0)); eq(Color.WHITE.getRGB(), image.getRGB(4, 71));
        for (int y = 3; y < 69; y++) eq(Color.ORANGE.getRGB(), image.getRGB(4, y));
        eq(Color.WHITE.getRGB(), image.getRGB(0, 30));
        return assertions;
    }
    private static void eq(Object expected, Object actual) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
}
