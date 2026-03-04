package com.luminaplayer.ui;

import com.luminaplayer.app.AppConfig;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

public class WhatsNewDialog extends Stage {

    public WhatsNewDialog(Window owner, List<String> stylesheets) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("What's New — " + AppConfig.APP_NAME);
        setMinWidth(860);
        setMinHeight(640);

        VBox container = new VBox(16);
        container.setPadding(new Insets(20));
        container.getStyleClass().add("whats-new-root");

        Label badge = new Label("NEW IN " + AppConfig.APP_NAME.toUpperCase());
        badge.getStyleClass().add("whats-new-badge");

        Label title = new Label("Architectural Innovations & Product Highlights");
        title.getStyleClass().add("whats-new-title");

        Label subtitle = new Label(
            "Offline-first AI subtitle generation with progressive playback, translation orchestration, and latency-first chunk scheduling.");
        subtitle.getStyleClass().add("whats-new-subtitle");
        subtitle.setWrapText(true);

        VBox cards = new VBox(12,
            createCard(
                "🔬  Novelty 1: .dsrt Progressive Subtitle Format",
                "Standard .srt is static. .dsrt is a living document that grows in real time as chunks complete.",
                "• Progressive loading while generation is still running\n" +
                    "• ConcurrentSkipListMap-backed cue index for O(log n) active cue lookup\n" +
                    "• Immediate subtitle availability before full transcription completes\n" +
                    "• Publishable as an open subtitle standard"
            ),
            createCard(
                "⚡  Novelty 2: Priority-Chunk Latency Pipeline",
                "LuminaPlayer processes the currently relevant chunk first to minimize time-to-first-subtitle.",
                "• Priority chunk is transcribed first (~3-second first result target)\n" +
                    "• Remaining chunks execute in parallel worker threads\n" +
                    "• Forward-first scheduling mirrors media buffering strategies\n" +
                    "• Priority chunk is upgraded later using higher model quality"
            ),
            createCard(
                "🧠  Novelty 3: Multi-Model Translation Verification",
                "A second model can verify and refine first-pass translations for better quality.",
                "• Multi-model and verified modes are built into orchestration\n" +
                    "• Translation providers are pluggable (Argos, LibreTranslate, Ollama)\n" +
                    "• Batch-aware and parallel-capable translation pipeline\n" +
                    "• Strong differentiator in subtitle tooling quality"
            ),
            createCard(
                "🏁  Offline-First AI Media Pipeline",
                "Core processing can run entirely on-device with no cloud requirement.",
                "• whisper.cpp for transcription\n" +
                    "• Argos Translate for offline Japanese→English translation\n" +
                    "• Local processing path supports privacy-sensitive workflows"
            )
        );

        Label footer = new Label(
            "Novelty Strength: .dsrt ⭐⭐⭐⭐⭐  |  Priority-chunk ⭐⭐⭐⭐  |  Multi-model verification ⭐⭐⭐⭐  |  Offline-first AI pipeline ⭐⭐⭐⭐⭐");
        footer.getStyleClass().add("whats-new-footer");
        footer.setWrapText(true);

        ScrollPane scrollPane = new ScrollPane(cards);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("whats-new-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Button closeButton = new Button("Start LuminaPlayer");
        closeButton.getStyleClass().add("whats-new-cta");
        closeButton.setOnAction(e -> close());

        HBox actions = new HBox(closeButton);
        actions.getStyleClass().add("whats-new-actions");

        container.getChildren().addAll(badge, title, subtitle, scrollPane, footer, actions);

        Scene scene = new Scene(container);
        if (stylesheets != null && !stylesheets.isEmpty()) {
            scene.getStylesheets().addAll(stylesheets);
        }

        setScene(scene);
    }

    private Node createCard(String titleText, String introText, String detailsText) {
        VBox card = new VBox(8);
        card.getStyleClass().add("whats-new-card");

        Label title = new Label(titleText);
        title.getStyleClass().add("whats-new-card-title");

        Label intro = new Label(introText);
        intro.getStyleClass().add("whats-new-card-intro");
        intro.setWrapText(true);

        Label details = new Label(detailsText);
        details.getStyleClass().add("whats-new-card-details");
        details.setWrapText(true);

        card.getChildren().addAll(title, intro, details);
        return card;
    }
}
