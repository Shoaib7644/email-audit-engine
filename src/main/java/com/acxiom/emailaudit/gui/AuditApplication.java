package com.acxiom.emailaudit.gui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image; // 1. Import the Image class
import javafx.stage.Stage;

public class AuditApplication extends Application {

    @Override
    public void start(Stage stage) {
        MainController controller = new MainController();
        Scene scene = new Scene(controller.getRoot(), 920, 680);

        // Inject our style rule file into the running frame asset context
        scene.getStylesheets().add(getClass().getResource("/styles/modern.css").toExternalForm());

        // 2. Set the custom application logo
        try {
            Image appIcon = new Image(getClass().getResourceAsStream("/images/app-logo.png"));
            stage.getIcons().add(appIcon);
        } catch (Exception e) {
            System.err.println("Could not load application icon: " + e.getMessage());
        }

        stage.setTitle("Email Audit Engine Suite Console");
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}