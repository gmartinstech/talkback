package net.martinstech.copiloto.ui;

import net.martinstech.copiloto.config.AppConfig;

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
    private final CheckBox speakResponsesBox;
    private final CheckBox speakThinkingBox;
    private final CheckBox webSearchBox;
    private final ComboBox<String> languageBox;

    public SettingsDialog(AppConfig current, List<String> models) {
        setTitle("Copiloto Configurações");
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

        speakResponsesBox = new CheckBox("Auto-ler respostas");
        speakResponsesBox.setSelected(current.speakResponses());

        speakThinkingBox = new CheckBox("Ler tokens de raciocínio");
        speakThinkingBox.setSelected(current.speakThinking());

        webSearchBox = new CheckBox("Ativar busca na web");
        webSearchBox.setSelected(current.webSearchEnabled());

        languageBox = new ComboBox<>();
        languageBox.getItems().addAll("pt-BR", "en-US");
        languageBox.setValue(current.language());

        grid.add(new Label("URL do Ollama"), 0, 0);
        grid.add(urlField, 1, 0);
        grid.add(new Label("Modelo"), 0, 1);
        grid.add(modelBox, 1, 1);
        grid.add(new Label("Motor TTS"), 0, 2);
        grid.add(ttsBox, 1, 2);
        grid.add(new Label("Idioma"), 0, 3);
        grid.add(languageBox, 1, 3);
        grid.add(speakResponsesBox, 0, 4);
        grid.add(speakThinkingBox, 0, 5);
        grid.add(webSearchBox, 0, 6);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                var result = new AppConfig(
                    current.enabled(),
                    current.voice(),
                    speakResponsesBox.isSelected(),
                    speakThinkingBox.isSelected(),
                    current.maxSpeakLength(),
                    urlField.getText(),
                    modelBox.getValue(),
                    ttsBox.getValue(),
                    webSearchBox.isSelected(),
                    languageBox.getValue(),
                    current.frontend()
                );
                return result;
            }
            return null;
        });
    }
}
