package ssoptimizer.standalone.game;

import org.apache.logging.log4j.LogManager;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ModSelectionNotice {
    private ModSelectionNotice() { }

    static void exit(int status, String message, Throwable cause) {
        show(status, message, cause);
        System.exit(status);
    }

    static void show(int status, String message, Throwable cause) {
        System.err.println("[SSOptimizer Standalone] " + message);
        var log = LogManager.getLogger(ModSelectionNotice.class);
        if (status == 75) log.info(message); else log.error(message, cause);
        if (!GraphicsEnvironment.isHeadless()) {
            Runnable show = () -> {
                JOptionPane pane = new JOptionPane(message,
                        status == 75 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                JDialog dialog = pane.createDialog(null, "SSOptimizer Standalone");
                Timer timer = new Timer(status == 75 ? 3500 : 12000, event -> dialog.dispose());
                timer.setRepeats(false);
                timer.start();
                try { dialog.setVisible(true); }
                finally { timer.stop(); dialog.dispose(); }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                show.run();
            } else {
                var finished = new CountDownLatch(1);
                SwingUtilities.invokeLater(() -> { try { show.run(); } finally { finished.countDown(); } });
                try { finished.await(status == 75 ? 5 : 14, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); log.warn("模组切换提示被中断", e); }
            }
        }
    }
}
