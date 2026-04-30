package net.martinstech.copiloto;

import javafx.application.Application;

/**
 * Launcher wrapper for CopilotoApp.
 *
 * <p>JavaFX requires that the main class not extend {@link Application}
 * when all classes are loaded from the classpath (non-modular mode).
 * This thin launcher delegates to {@link CopilotoApp}.
 */
public class CopilotoLauncher {
    public static void main(String[] args) {
        Application.launch(CopilotoApp.class, args);
    }
}
