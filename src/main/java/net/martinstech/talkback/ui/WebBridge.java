package net.martinstech.talkback.ui;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
    private final Function<String, String> onSkillsSearch;
    private final Supplier<String> onSkillsList;
    private final Function<String, Boolean> onSkillsAdd;
    private final Function<String, Boolean> onSkillsRemove;
    private final Supplier<Boolean> onSkillsUpdate;
    private final Function<String, String> onSkillsDetails;
    private final Consumer<String> onSpeakText;

    public WebBridge(Consumer<String> onMessage,
                     Consumer<String> onSpeakText,
                     Consumer<String> onOpenFile,
                     Runnable onShowSettings,
                     Consumer<String> onModelChanged,
                     Runnable onMinimize,
                     Runnable onClose,
                     Function<String, String> onSkillsSearch,
                     Supplier<String> onSkillsList,
                     Function<String, Boolean> onSkillsAdd,
                     Function<String, Boolean> onSkillsRemove,
                     Supplier<Boolean> onSkillsUpdate,
                     Function<String, String> onSkillsDetails) {
        this.onMessage = onMessage;
        this.onOpenFile = onOpenFile;
        this.onShowSettings = onShowSettings;
        this.onModelChanged = onModelChanged;
        this.onMinimize = onMinimize;
        this.onClose = onClose;
        this.onSkillsSearch = onSkillsSearch;
        this.onSkillsList = onSkillsList;
        this.onSkillsAdd = onSkillsAdd;
        this.onSkillsRemove = onSkillsRemove;
        this.onSkillsUpdate = onSkillsUpdate;
        this.onSkillsDetails = onSkillsDetails;
        this.onSpeakText = onSpeakText;
    }

    public void speakText(String text) {
        if (text != null && !text.isBlank()) {
            Platform.runLater(() -> onSpeakText.accept(text));
        }
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

    public String skillsSearch(String query) {
        return onSkillsSearch.apply(query);
    }

    public String skillsList() {
        return onSkillsList.get();
    }

    public boolean skillsAdd(String packageSpec) {
        return onSkillsAdd.apply(packageSpec);
    }

    public boolean skillsRemove(String skillName) {
        return onSkillsRemove.apply(skillName);
    }

    public boolean skillsUpdate() {
        return onSkillsUpdate.get();
    }

    public String skillsDetails(String skillPath) {
        return onSkillsDetails.apply(skillPath);
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
