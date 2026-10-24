package com.nexusid.ui;

import com.nexusid.model.User;
import com.nexusid.service.AuthService;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

public class LoginView {
    private final AuthService auth = new AuthService();

    public Pane build(App app) {
        StackPane root = new StackPane();
        root.getStyleClass().add("aurora-login-root");
        VBox card = new VBox(18);
        card.getStyleClass().add("aurora-login-card");
        card.setMaxWidth(470);
        card.setPadding(new Insets(38));

        Label eyebrow = new Label("SMART CAMPUS PLATFORM");
        eyebrow.getStyleClass().add("aurora-eyebrow");
        ImageView brand = new ImageView(new Image(LoginView.class.getResourceAsStream("/branding/nexusid-primary.png")));
        brand.setPreserveRatio(true);
        brand.setFitWidth(330);
        brand.setFitHeight(128);
        brand.setSmooth(true);
        brand.setAccessibleText("NexusID — One Identity. Infinite Possibilities.");
        Label sub = new Label("UNIVERSITY  /  IDENTITY & MANAGEMENT");
        sub.getStyleClass().add("aurora-login-subtitle");
        sub.setWrapText(true);
        Separator separator = new Separator();
        Label prompt = new Label("Welcome back");
        prompt.getStyleClass().add("aurora-login-prompt");
        Label helper = new Label("Sign in to continue to your campus workspace.");
        helper.getStyleClass().add("aurora-muted");

        TextField email = new TextField();
        email.setPromptText("University email");
        email.setAccessibleText("University email");
        PasswordField pass = new PasswordField();
        pass.setPromptText("Password");
        pass.setAccessibleText("Password");
        Button login = App.btn("SIGN IN  →");
        login.getStyleClass().add("aurora-primary-button");
        Label hint = new Label("Demo account: student@nexusid.edu / password");
        hint.getStyleClass().add("aurora-login-hint");
        hint.setWrapText(true);

        login.setOnAction(e -> {
            try {
                User u = auth.login(email.getText().trim().toLowerCase(), pass.getText());
                if (u == null) new Alert(Alert.AlertType.ERROR, "Invalid credentials or inactive account.").showAndWait();
                else app.showDashboard(u);
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Database error: " + ex.getMessage()).showAndWait();
            }
        });
        card.getChildren().addAll(brand, eyebrow, sub, separator, prompt, helper, email, pass, login, hint);
        root.getChildren().add(card);
        return root;
    }
}
