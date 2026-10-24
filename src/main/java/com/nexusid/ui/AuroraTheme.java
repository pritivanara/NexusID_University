package com.nexusid.ui;

import javafx.scene.Scene;

/** Shared visual theme for the JavaFX desktop application. */
public final class AuroraTheme {
    private AuroraTheme() {}

    public static Scene attach(Scene scene) {
        var css = AuroraTheme.class.getResource("/nexusid-aurora.css");
        if (css != null && !scene.getStylesheets().contains(css.toExternalForm())) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        return scene;
    }
}
