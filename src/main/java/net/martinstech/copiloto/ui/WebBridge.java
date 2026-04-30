package net.martinstech.copiloto.ui;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import net.martinstech.copiloto.service.SkillsService;
import netscape.javascript.JSObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
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
    private final Function<String, SkillsService.SkillResult> onSkillsAdd;
    private final Function<String, SkillsService.SkillResult> onSkillsRemove;
    private final Supplier<SkillsService.SkillResult> onSkillsUpdate;
    private final Function<String, String> onSkillsDetails;
    private final Consumer<String> onSpeakText;
    private final Supplier<String> onGetLanguage;
    private final Consumer<ImageMessage> onImageMessage;
    private Window ownerWindow;

    public WebBridge(Consumer<String> onMessage,
                     Consumer<String> onSpeakText,
                     Consumer<String> onOpenFile,
                     Runnable onShowSettings,
                     Consumer<String> onModelChanged,
                     Runnable onMinimize,
                     Runnable onClose,
                     Function<String, String> onSkillsSearch,
                     Supplier<String> onSkillsList,
                     Function<String, net.martinstech.copiloto.service.SkillsService.SkillResult> onSkillsAdd,
                     Function<String, net.martinstech.copiloto.service.SkillsService.SkillResult> onSkillsRemove,
                     Supplier<net.martinstech.copiloto.service.SkillsService.SkillResult> onSkillsUpdate,
                     Function<String, String> onSkillsDetails,
                     Supplier<String> onGetLanguage,
                     Consumer<ImageMessage> onImageMessage) {
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
        this.onGetLanguage = onGetLanguage;
        this.onImageMessage = onImageMessage;
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
        var result = onSkillsAdd.apply(packageSpec);
        return result != null && result.success();
    }

    public boolean skillsRemove(String skillName) {
        var result = onSkillsRemove.apply(skillName);
        return result != null && result.success();
    }

    public boolean skillsUpdate() {
        var result = onSkillsUpdate.get();
        return result != null && result.success();
    }

    public String skillsDetails(String skillPath) {
        return onSkillsDetails.apply(skillPath);
    }

    public String getLanguage() {
        return onGetLanguage.get();
    }

    /**
     * Installs this bridge into the given {@link WebEngine}.
     * The bridge is injected after the page loads successfully.
     */
    public void install(WebEngine engine, Window ownerWindow) {
        this.ownerWindow = ownerWindow;
        engine.getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("copiloto", this);
            }
        });
    }

    /**
     * Opens a {@link FileChooser} for the user to pick a text file or image.
     * Returns a JSON string with {@code name}, {@code type} (text/image), and
     * {@code content} (UTF-8 text for text files, Base64 for images).
     * Returns an empty JSON object {@code {}} if the user cancels.
     */
    public String pickDocument() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Anexar documento");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Documentos suportados", "*.txt", "*.md", "*.json", "*.java", "*.py", "*.html", "*.css", "*.js", "*.xml", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("Texto", "*.txt", "*.md", "*.json", "*.java", "*.py", "*.html", "*.css", "*.js", "*.xml"),
                new FileChooser.ExtensionFilter("Imagem", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("Todos os arquivos", "*.*")
        );
        File file = chooser.showOpenDialog(ownerWindow);
        if (file == null) {
            return "{}";
        }
        try {
            String name = file.getName();
            String lower = name.toLowerCase();
            boolean isImage = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp");
            if (isImage) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String base64 = Base64.getEncoder().encodeToString(bytes);
                return "{\"name\":\"" + escapeJson(name) + "\",\"type\":\"image\",\"content\":\"" + escapeJson(base64) + "\"}";
            } else {
                String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                return "{\"name\":\"" + escapeJson(name) + "\",\"type\":\"text\",\"content\":\"" + escapeJson(text) + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Sends an image message to the application backend.
     */
    public void sendImage(String text, String name, String base64Image) {
        Platform.runLater(() -> onImageMessage.accept(new ImageMessage(text, name, base64Image)));
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Immutable data holder for image messages sent from the WebView.
     */
    public record ImageMessage(String text, String name, String base64Image) {
    }
}
