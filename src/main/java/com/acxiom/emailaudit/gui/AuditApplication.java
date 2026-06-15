package com.acxiom.emailaudit.gui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class AuditApplication extends Application {

    @Override
    public void start(Stage stage) {

        MainController controller = new MainController();

        Scene scene = new Scene(
                controller.getRoot(),
                900,
                600);

        stage.setTitle("Email Audit Engine");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}