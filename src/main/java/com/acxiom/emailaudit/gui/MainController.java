package com.acxiom.emailaudit.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.awt.Desktop;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.reporting.ExcelExporter;

public class MainController {

    private final VBox      root        = new VBox();
    private final ProgressBar progressBar = new ProgressBar();
    private final TextArea  logArea     = new TextArea();

    private AuditOrchestrator.RunSummary lastRunSummary;
    private Path generatedExcelPath;

    private final Button runButton              = new Button("Run Audit Engine");
    private final Button openSummaryButton      = new Button("Open Summary Excel");
    private final Button openReportButton       = new Button("Open Report in Chrome");
    private final Button openReportsFolderButton = new Button("Open Directory");

    public MainController() {
        root.setPadding(new Insets(24));
        root.setSpacing(20);
        root.getStyleClass().add("main-container");

        VBox headerPanel = new VBox(4);
        headerPanel.getStyleClass().add("header-panel");
        Label titleLabel = new Label("EMAIL AUDIT CONSOLE");
        titleLabel.getStyleClass().add("header-title");
        Label subtitleLabel = new Label("Playwright Automated Email Verification Tool");
        subtitleLabel.getStyleClass().add("header-subtitle");
        headerPanel.getChildren().addAll(titleLabel, subtitleLabel);

        HBox environmentBox = new HBox(16);
        environmentBox.setAlignment(Pos.CENTER_LEFT);
        VBox inputCard  = createInfoCard("INPUT SOURCE DIRECTORY", "./input");
        VBox outputCard = createInfoCard("OUTPUT DESTINATION",     "./output");
        HBox.setHgrow(inputCard,  Priority.ALWAYS);
        HBox.setHgrow(outputCard, Priority.ALWAYS);
        environmentBox.getChildren().addAll(inputCard, outputCard);

        GridPane actionGrid = new GridPane();
        actionGrid.setHgap(12);
        actionGrid.setVgap(12);

        runButton.getStyleClass().add("btn-primary");
        openSummaryButton.getStyleClass().add("btn-secondary");
        openReportButton.getStyleClass().add("btn-secondary");
        openReportsFolderButton.getStyleClass().add("btn-secondary");

        runButton.setMaxWidth(Double.MAX_VALUE);
        openSummaryButton.setMaxWidth(Double.MAX_VALUE);
        openReportButton.setMaxWidth(Double.MAX_VALUE);
        openReportsFolderButton.setMaxWidth(Double.MAX_VALUE);

        actionGrid.add(runButton,               0, 0);
        actionGrid.add(openSummaryButton,       1, 0);
        actionGrid.add(openReportButton,        0, 1);
        actionGrid.add(openReportsFolderButton, 1, 1);

        ColumnConstraints col = new ColumnConstraints();
        col.setPercentWidth(50);
        actionGrid.getColumnConstraints().addAll(col, col);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setProgress(0);
        progressBar.getStyleClass().add("custom-progress-bar");

        VBox consoleContainer = new VBox(8);
        consoleContainer.getStyleClass().add("console-container");
        Label consoleLabel = new Label("REAL-TIME AUTOMATION LOGS");
        consoleLabel.getStyleClass().add("console-header-label");
        logArea.setEditable(false);
        logArea.setPrefHeight(280);
        logArea.getStyleClass().add("modern-log-area");
        consoleContainer.getChildren().addAll(consoleLabel, logArea);
        VBox.setVgrow(consoleContainer, Priority.ALWAYS);

        openSummaryButton.setDisable(true);
        openReportButton.setDisable(true);
        openReportsFolderButton.setDisable(true);

        root.getChildren().addAll(
                headerPanel,
                environmentBox,
                actionGrid,
                progressBar,
                consoleContainer);

        runButton.setOnAction(e -> runAudit());
        openSummaryButton.setOnAction(e -> openSummaryExcel());
        openReportButton.setOnAction(e -> openReportInChrome());
        openReportsFolderButton.setOnAction(e -> openReportsFolder());
    }

    private VBox createInfoCard(String title, String pathValue) {
        VBox card = new VBox(4);
        card.getStyleClass().add("info-card");
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("card-title");
        Label valLbl = new Label(pathValue);
        valLbl.getStyleClass().add("card-value");
        card.getChildren().addAll(titleLbl, valLbl);
        return card;
    }

    public Parent getRoot() {
        return root;
    }

    private void runAudit() {
        runButton.setDisable(true);
        openSummaryButton.setDisable(true);
        openReportButton.setDisable(true);
        openReportsFolderButton.setDisable(true);
        logArea.clear();

        AuditTask task = new AuditTask();
        progressBar.progressProperty().bind(task.progressProperty());

        task.messageProperty().addListener((obs, oldVal, newVal) ->
                logArea.appendText("» " + newVal + "\n"));

        task.setOnSucceeded(event -> {
            runButton.setDisable(false);
            openSummaryButton.setDisable(false);
            openReportButton.setDisable(false);
            openReportsFolderButton.setDisable(false);

            final AuditOrchestrator.RunSummary summary = task.getValue();
            lastRunSummary   = summary;
            generatedExcelPath = ExcelExporter.export(summary);

            // ── Execution time formatting ──────────────────────────────────
            final String execTime = formatDuration(summary.executionTimeMs());

            // ── Dashboard path (prefer custom dashboard over spark report) ──
            final String dashboardDisplay =
                    summary.dashboardPath() != null
                            ? summary.dashboardPath().toString()
                            : summary.reportPath() != null
                              ? summary.reportPath().toString()
                              : "N/A";

            logArea.appendText(
                    "\n=========================================\n"  +
                            "  AUDIT PIPELINE COMPLETE\n"                    +
                            "=========================================\n"    +
                            " Total Discovered : " + summary.totalDiscovered()  + "\n" +
                            " Processed        : " + summary.processed()        + "\n" +
                            " Skipped          : " + summary.skipped()          + "\n" +
                            " Succeeded        : " + summary.succeeded()        + "\n" +
                            " Failed           : " + summary.failed()           + "\n" +
                            " Errors           : " + summary.errored()          + "\n" +
                            " Execution Time   : " + execTime                   + "\n" +
                            "-----------------------------------------\n"    +
                            " Dashboard        : " + dashboardDisplay           + "\n" +
                            " Report           : " + summary.reportPath()       + "\n" +
                            " Summary Sheet    : " + generatedExcelPath         + "\n" +
                            "=========================================\n"
            );
        });

        task.setOnFailed(event -> {
            runButton.setDisable(false);
            logArea.appendText("CRITICAL PIPELINE EXCEPTION: "
                    + task.getException().getMessage() + "\n");
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Formats a millisecond duration into a human-readable string.
     * e.g. 450 → "450ms", 3500 → "3s 500ms", 75000 → "1m 15s"
     */
    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long s   = ms / 1000;
        long rem = ms % 1000;
        if (s < 60) return s + "s" + (rem > 0 ? " " + rem + "ms" : "");
        long m  = s / 60;
        long rs = s % 60;
        if (m < 60) return m + "m" + (rs > 0 ? " " + rs + "s" : "");
        long h  = m / 60;
        long rm = m % 60;
        return h + "h" + (rm > 0 ? " " + rm + "m" : "") + (rs > 0 ? " " + rs + "s" : "");
    }

    private void openSummaryExcel() {
        try {
            if (generatedExcelPath == null || !generatedExcelPath.toFile().exists()) {
                logArea.appendText(" System Error: Targets unavailable or completely clean.\n");
                return;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(generatedExcelPath.toFile());
                logArea.appendText(" Opening external environment Excel process workspace.\n");
            }
        } catch (Exception ex) {
            logArea.appendText(" Direct File Pointer Reference Exception: " + ex.getMessage() + "\n");
        }
    }

    private void openReportInChrome() {
        try {
            if (lastRunSummary == null) return;

            // Prefer the custom dashboard; fall back to the spark report
            Path reportToOpen =
                    (lastRunSummary.dashboardPath() != null
                            && lastRunSummary.dashboardPath().toFile().exists())
                            ? lastRunSummary.dashboardPath()
                            : lastRunSummary.reportPath();

            if (reportToOpen == null) {
                logArea.appendText(" Missing visualization engine paths.\n");
                return;
            }

            String        targetUri     = reportToOpen.toUri().toString();
            String        os            = System.getProperty("os.name").toLowerCase();
            ProcessBuilder processBuilder = new ProcessBuilder();

            if (os.contains("win")) {
                processBuilder.command("cmd.exe", "/c", "start", "chrome", targetUri);
            } else if (os.contains("mac")) {
                processBuilder.command("open", "-a", "Google Chrome", targetUri);
            } else {
                processBuilder.command("google-chrome", targetUri);
            }

            processBuilder.start();
            logArea.appendText(" Initiating sandbox browser session in Google Chrome...\n");
        } catch (Exception ex) {
            logArea.appendText(" Failed direct launch fallback executing default browse stack.\n");
        }
    }

    private void openReportsFolder() {
        try {
            Desktop.getDesktop().open(Paths.get("output/reports").toFile());
        } catch (Exception ex) {
            logArea.appendText(" File system lock access path restriction error.\n");
        }
    }
}