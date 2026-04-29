package net.martinstech.talkback.ui;

import net.martinstech.talkback.config.AppConfig;

import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;

import java.util.List;

/**
 * Modal settings dialog for configuring Ollama, model and TTS engine.
 */
public class SettingsDialog extends Dialog<AppConfig> {
    private final TextField urlField;
    private final ComboBox<String> modelBox;
    private final ComboBox<String> ttsBox;

    public SettingsDialog(AppConfig current, List<String> models) {
        setTitle("TalkBack Settings");
        initModality(Modality.APPLICATION_MODAL);

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        urlField = new TextField(current.ollamaUrl());
        modelBox = new ComboBox<>();
        modelBox.setEditable(true);
        modelBox.getItems().addAll(models);
        if (!models.isEmpty() && models.contains(current.ollamaModel())) {
            modelBox.setValue(current.ollamaModel());
        } else if (!models.isEmpty()) {
            modelBox.setValue(models.get(0));
        } else {
            modelBox.setValue(current.ollamaModel());
        }

        ttsBox = new ComboBox<>();
        ttsBox.getItems().addAll("edge", "sapi", "kokoro", "qwen");
        ttsBox.setValue(current.ttsEngine());

        grid.add(new Label("Ollama URL"), 0, 0);
        grid.add(urlField, 1, 0);
        grid.add(new Label("Model"), 0, 1);
        grid.add(modelBox, 1, 1);
        grid.add(new Label("TTS Engine"), 0, 2);
        grid.add(ttsBox, 1, 2);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return new AppConfig(
                    current.enabled(),
                    current.voice(),
                    current.speakResponses(),
                    current.speakThinking(),
                    current.maxSpeakLength(),
                    urlField.getText(),
                    modelBox.getValue(),
                    ttsBox.getValue(),
                    current.frontend()
                );
            }
            return null;
        });
    }
}
