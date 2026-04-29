package net.martinstech.talkback.ui;

import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * A popup window for viewing syntax-highlighted diffs.
 * Renders the diff inside a {@link WebView} with maritime styling.
 */
public class CodeViewerStage {
    private final Stage stage;
    private final WebView webView;

    public CodeViewerStage() {
        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.initModality(Modality.NONE);
        stage.setWidth(800);
        stage.setHeight(600);
        stage.setTitle("TalkBack Diff Viewer");

        webView = new WebView();
        var engine = webView.getEngine();
        engine.loadContent(
            "<!DOCTYPE html>\n" +
            "<html><head><style>\n" +
            "body{background:#0C1222;color:#F1F5F9;font-family:'Fira Code',Consolas,monospace;font-size:13px;margin:0;padding:16px}\n" +
            "pre{white-space:pre-wrap;word-wrap:break-word;line-height:1.6}\n" +
            ".add{color:#34D399}\n" +
            ".del{color:#F87171}\n" +
            ".info{color:#94A3B8}\n" +
            "</style></head><body><pre id='content'>Loading diff...</pre>\n" +
            "<script>window.setDiff=function(text){document.getElementById('content').innerHTML=text;}</script>\n" +
            "</body></html>"
        );

        var scene = new Scene(webView);
        stage.setScene(scene);
    }

    public void showDiff(String diff) {
        StringBuilder html = new StringBuilder();
        for (String line : diff.lines().toList()) {
            String escaped = escapeHtml(line);
            String css;
            if (line.startsWith("+")) {
                css = "add";
            } else if (line.startsWith("-")) {
                css = "del";
            } else if (line.startsWith("@@") || line.startsWith("diff") || line.startsWith("index") || line.startsWith("---") || line.startsWith("+++")) {
                css = "info";
            } else {
                css = "";
            }
            html.append("<div class='").append(css).append("'>").append(escaped).append("</div>\n");
        }

        var engine = webView.getEngine();
        if (engine.getLoadWorker().getState() == javafx.concurrent.Worker.State.SUCCEEDED) {
            engine.executeScript("window.setDiff(`" + html.toString().replace("`", "\\`") + "`);");
        } else {
            engine.getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
                if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                    engine.executeScript("window.setDiff(`" + html.toString().replace("`", "\\`") + "`);");
                }
            });
        }

        stage.show();
        stage.toFront();
    }

    private String escapeHtml(String text) {
        return text.replace("\u0026", "\u0026amp;")
                   .replace("\u003c", "\u0026lt;")
                   .replace("\u003e", "\u0026gt;");
    }

    public Stage getStage() {
        return stage;
    }
}
