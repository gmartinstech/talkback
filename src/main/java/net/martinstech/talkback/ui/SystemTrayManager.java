package net.martinstech.talkback.ui;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Manages the AWT system tray icon for TalkBack.
 */
public class SystemTrayManager {
    private final Runnable onToggle;
    private TrayIcon trayIcon;

    public SystemTrayManager(Runnable onToggle) {
        this.onToggle = onToggle;
    }

    /**
     * Installs the tray icon if the platform supports it.
     */
    public void install() {
        if (!SystemTray.isSupported()) {
            System.err.println("System tray not supported");
            return;
        }

        var tray = SystemTray.getSystemTray();
        trayIcon = new TrayIcon(createIcon(), "TalkBack");
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> onToggle.run());

        var menu = new PopupMenu();
        var showItem = new MenuItem("Mostrar Chat");
        showItem.addActionListener(e -> onToggle.run());
        menu.add(showItem);

        var exitItem = new MenuItem("Sair");
        exitItem.addActionListener(e -> System.exit(0));
        menu.addSeparator();
        menu.add(exitItem);

        trayIcon.setPopupMenu(menu);
        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            System.err.println("Failed to add tray icon: " + e.getMessage());
        }
    }

    private Image createIcon() {
        int size = 64;
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                           java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x00, 0x35, 0x66));
        g.fillOval(4, 4, size - 8, size - 8);
        g.setColor(new Color(0xF5, 0xCC, 0x00));
        g.fillOval(size / 2 - 8, size / 2 - 8, 16, 16);
        g.dispose();
        return img;
    }
}
