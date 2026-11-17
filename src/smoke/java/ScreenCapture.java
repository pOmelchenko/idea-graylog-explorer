import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.io.File;

/** Captures only the private Xvfb display started by smoke_test.py. */
public final class ScreenCapture {
    public static void main(String[] args) throws Exception {
        ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", new File(args[0]));
    }
}
