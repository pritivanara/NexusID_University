package com.nexusid.ui;

import com.nexusid.model.User;
import com.nexusid.db.Database;
import com.nexusid.service.CameraBiometricService;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class App extends Application {
    private Stage stage;
    private User user;
    private final LoginView login = new LoginView();

    @Override
    public void start(Stage s) {
        stage = s;
        stage.setTitle("NexusID University");
        try { stage.getIcons().add(new Image(App.class.getResourceAsStream("/branding/nexusid-app-icon.png"))); } catch (Exception ignored) { }
        try {
            Database.ensureDevUsers();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Database setup error: " + ex.getMessage() +
                    "\n\nCheck MySQL is running and AppConfig credentials are correct.").showAndWait();
        }
        showLogin();
        stage.show();
    }

    void showLogin() {
        stage.setScene(AuroraTheme.attach(new Scene(login.build(this), 1000, 680)));
    }

    void showDashboard(User u) {
        user = u;
        stage.setScene(AuroraTheme.attach(new Scene(new DashboardView(this, u).build(), 1360, 860)));
    }

    void logout() { user = null; showLogin(); }

    @Override public void stop() { CameraBiometricService.shutdownCamera(); }
    public static void main(String[] a) { launch(a); }

    static VBox box(double gap) { VBox b = new VBox(gap); b.setPadding(new Insets(20)); return b; }
    static Label title(String s) { Label l = new Label(s); l.getStyleClass().add("aurora-page-title"); return l; }
    static Button btn(String s) { Button b = new Button(s); b.setMaxWidth(Double.MAX_VALUE); b.setPrefHeight(42); return b; }
}
