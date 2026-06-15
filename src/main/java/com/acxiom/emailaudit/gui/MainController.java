package com.acxiom.emailaudit.gui;

import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.awt.Desktop;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.reporting.ExcelExporter;

public class MainController {

    private final VBox root = new VBox();

    private final ProgressBar progressBar =
            new ProgressBar();

    private final TextArea logArea =
            new TextArea();
    private AuditOrchestrator.RunSummary lastRunSummary;

    private final Button runButton =
            new Button("Run Audit");
    private final Button downloadSummaryButton =
            new Button("Download Summary Excel");

    private final Button openReportButton =
            new Button("Open Report");

    private final Button openReportsFolderButton =
            new Button("Open Reports Folder");

    public MainController() {

        root.setPadding(new Insets(20));
        root.setSpacing(15);

        Label inputInfo =
                new Label("Input Folder: ./input");

        Label outputInfo =
                new Label("Output Folder: ./output");

        logArea.setEditable(false);
        downloadSummaryButton.setDisable(true);
        openReportButton.setDisable(true);
        openReportsFolderButton.setDisable(true);

        root.getChildren().addAll(
                inputInfo,
                outputInfo,
                runButton,
                downloadSummaryButton,
                openReportButton,
                openReportsFolderButton,
                progressBar,
                logArea
        );

        runButton.setOnAction(e -> runAudit());
        downloadSummaryButton.setOnAction(
                e -> downloadSummary());

        openReportButton.setOnAction(
                e -> openReport());

        openReportsFolderButton.setOnAction(
                e -> openReportsFolder());
    }

    public Parent getRoot() {
        return root;
    }

    private void runAudit() {

        runButton.setDisable(true);

        AuditTask task = new AuditTask();

        progressBar.progressProperty()
                .bind(task.progressProperty());

        task.messageProperty()
                .addListener((obs, oldValue, newValue) ->
                        logArea.appendText(newValue + "\n"));

        task.setOnSucceeded(event -> {

            runButton.setDisable(false);
            downloadSummaryButton.setDisable(false);
            openReportButton.setDisable(false);
            openReportsFolderButton.setDisable(false);

            var summary = task.getValue();
            var excelPath =
                    ExcelExporter.export(summary);
            lastRunSummary = summary;

            logArea.appendText(
                    "\nAudit Complete\n" +
                            "Processed: " + summary.processed() + "\n" +
                            "Succeeded: " + summary.succeeded() + "\n" +
                            "Failed: " + summary.failed() + "\n" +
                            "Report: " + summary.reportPath() + "\n"
            );
            logArea.appendText(
                    "Excel Summary Report: "
                            + excelPath
                            + "\n");
        });

        task.setOnFailed(event -> {

            runButton.setDisable(false);

            logArea.appendText(
                    "ERROR: " +
                            task.getException().getMessage() +
                            "\n");
        });

        Thread thread = new Thread(task);

        thread.setDaemon(true);

        thread.start();
    }
    private void downloadSummary() {

        try {

            logArea.appendText(
                    "Business Summary Excel generation coming in Phase 2\n");

        } catch (Exception ex) {

            logArea.appendText(
                    "ERROR: "
                            + ex.getMessage()
                            + "\n");
        }
    }
    private void openReport() {

        try {

            if (lastRunSummary == null) {
                return;
            }

            Desktop.getDesktop()
                    .browse(lastRunSummary.reportPath().toUri());

        } catch (Exception ex) {

            logArea.appendText(
                    "Unable to open report\n");
        }
    }
    private void openReportsFolder() {

        try {

            Desktop.getDesktop()
                    .open(Paths.get("output/reports").toFile());

        } catch (Exception ex) {

            logArea.appendText(
                    "Unable to open reports folder\n");
        }
    }
}