package net.martinstech.talkback.ui;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;

import java.util.function.Consumer;

/**
 * Bridge object exposed to the WebView JavaScript context as {@code window.talkback}.
 */
@SuppressWarnings("removal")
public class WebBridge {
    private final Consumer<String> onMessage;
    private final Consumer<String> onOpenFile;
    private final Runnable onShowSettings;
    private final Consumer<String> onModelChanged;
    private final Runnable onMinimize;
    private final Runnable onClose;

    public WebBridge(Consumer<String> onMessage,
                     Consumer<String> onOpenFile,
                     Runnable onShowSettings,
                     Consumer<String> onModelChanged,
                     Runnable onMinimize,
                     Runnable onClose) {
        this.onMessage = onMessage;
        this.onOpenFile = onOpenFile;
        this.onShowSettings = onShowSettings;
        this.onModelChanged = onModelChanged;
        this.onMinimize = onMinimize;
        this.onClose = onClose;
    }

    public void send(String text) {
        Platform.runLater(() -> onMessage.accept(text));
    }

    public void openFile(String path) {
        Platform.runLater(() -> onOpenFile.accept(path));
    }

    public void showSettings() {
        Platform.runLater(onShowSettings);
    }

    public void modelChanged(String model) {
        Platform.runLater(() -> onModelChanged.accept(model));
    }

    public void minimize() {
        Platform.runLater(onMinimize);
    }

    public void close() {
        Platform.runLater(onClose);
    }

    /**
     * Installs this bridge into the given {@link WebEngine}.
     * The bridge is injected after the page loads successfully.
     */
    public void install(WebEngine engine) {
        engine.getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("talkback", this);
            }
        });
    }
}
