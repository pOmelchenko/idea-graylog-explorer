package dev.graylog.jetbrains;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.file.Path;

/** Captures only the relevant UI surface from the synthetic isolated smoke project. */
final class ReleaseScreenshots {
    static void capture(JComponent component, Path destination) throws Exception {
        var location = component.getLocationOnScreen();
        Rectangle bounds = new Rectangle(location.x, location.y, component.getWidth(), component.getHeight());
        ImageIO.write(new Robot().createScreenCapture(bounds), "png", destination.toFile());
    }
}
