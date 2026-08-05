package com.acxiom.emailaudit.gui;

import com.acxiom.emailaudit.campaign.CampaignSpecification;
import com.acxiom.emailaudit.campaign.CampaignSpecificationModule;
import com.acxiom.emailaudit.core.ClientContext;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.reporting.ExcelExporter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class MainController {

    private final VBox      root        = new VBox();
    private final ProgressBar progressBar = new ProgressBar();
    private final TextArea  logArea     = new TextArea();

    private AuditOrchestrator.RunSummary lastRunSummary;
    private Path generatedExcelPath;
    private Path campaignSpecificationPath;
    private CampaignSpecification selectedCampaignSpecification;
    private boolean specificationPendingValidation;
    private boolean campaignSpecificationExpanded;

    private final Button runButton              = new Button("Run Audit Engine");
    private final Button openSummaryButton      = new Button("Open Summary Excel");
    private final Button openReportButton       = new Button("Open Dashboard");
    private final Button openReportsFolderButton = new Button("Open Output Folder");
    private final Button campaignSpecificationToggleButton =
            new Button("▶ Campaign Specification (Optional)");
    private final ComboBox<String> clientComboBox = new ComboBox<>();
    private final TextField specificationFileField = new TextField();
    private final ComboBox<String> worksheetComboBox = new ComboBox<>();
    private final Button browseSpecificationButton = new Button("Browse...");
    private final Button validateSpecificationButton = new Button("Validate");
    private final Label specificationStatusLabel = new Label("No Campaign Specification Selected");
    private final Label specificationFileNameLabel = new Label("No spreadsheet selected");
    private final Label specificationWorksheetNameLabel = new Label("-");
    private final Label specificationEntriesLabel = new Label("-");
    private final Label specificationHintLabel = new Label("Campaign validation will be skipped.");
    private final VBox campaignSpecificationContent = new VBox(8);

    public MainController() {
        root.setPadding(new Insets(14));
        root.setSpacing(10);
        root.getStyleClass().add("main-container");

        VBox headerPanel = new VBox(4);
        headerPanel.getStyleClass().add("header-panel");
        Label titleLabel = new Label("EMAIL AUDIT CONSOLE");
        titleLabel.getStyleClass().add("header-title");
        Label subtitleLabel = new Label("Playwright Automated Email Verification Tool");
        subtitleLabel.getStyleClass().add("header-subtitle");
        headerPanel.getChildren().addAll(titleLabel, subtitleLabel);

        HBox environmentBox = new HBox(10);
        environmentBox.setAlignment(Pos.CENTER_LEFT);
        VBox inputCard  = createCompactPathInfo("📂 Input", "./input");
        VBox outputCard = createCompactPathInfo("📂 Output", "./output");
        HBox.setHgrow(inputCard,  Priority.ALWAYS);
        HBox.setHgrow(outputCard, Priority.ALWAYS);
        environmentBox.getChildren().addAll(inputCard, outputCard);

        VBox clientPanel = createClientPanel();
        VBox campaignSpecificationPanel = createCampaignSpecificationPanel();
        VBox executionPanel = createExecutionPanel();

        runButton.getStyleClass().add("btn-primary");
        openSummaryButton.getStyleClass().add("btn-secondary");
        openReportButton.getStyleClass().add("btn-secondary");
        openReportsFolderButton.getStyleClass().add("btn-secondary");

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
                clientPanel,
                campaignSpecificationPanel,
                executionPanel,
                progressBar,
                consoleContainer);

        runButton.setOnAction(e -> runAudit());
        openReportButton.setOnAction(e -> openReportInChrome());
        openSummaryButton.setOnAction(e -> openSummaryExcel());
        openReportsFolderButton.setOnAction(e -> openReportsFolder());
    }

    private VBox createClientPanel() {
        VBox panel = new VBox(6);
        panel.getStyleClass().add("compact-section");

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Label label = new Label("Client");
        label.getStyleClass().add("compact-field-label");
        label.setMinWidth(100);

        clientComboBox.getItems().setAll(ClientContext.supportedClients());
        clientComboBox.getSelectionModel().select(ClientContext.DEFAULT_CLIENT);
        clientComboBox.setMaxWidth(220);
        ClientContext.setSelectedClient(ClientContext.DEFAULT_CLIENT);
        clientComboBox.valueProperty().addListener((obs, oldValue, newValue) ->
                ClientContext.setSelectedClient(newValue));

        row.getChildren().addAll(label, clientComboBox);
        panel.getChildren().add(row);
        return panel;
    }

    private VBox createCampaignSpecificationPanel() {
        VBox panel = new VBox(6);
        panel.getStyleClass().add("compact-section");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);

        Label fileLabel = new Label("Spreadsheet");
        Label worksheetLabel = new Label("Worksheet");
        fileLabel.getStyleClass().add("compact-field-label");
        worksheetLabel.getStyleClass().add("compact-field-label");

        specificationFileField.setEditable(false);
        specificationFileField.setPromptText("No file selected");
        specificationFileField.setMaxWidth(Double.MAX_VALUE);
        worksheetComboBox.setPromptText("Select Worksheet");
        worksheetComboBox.setDisable(true);
        worksheetComboBox.setMaxWidth(Double.MAX_VALUE);
        validateSpecificationButton.setDisable(true);
        browseSpecificationButton.getStyleClass().add("btn-secondary");
        validateSpecificationButton.getStyleClass().add("btn-secondary");
        campaignSpecificationToggleButton.getStyleClass().add("section-toggle");
        campaignSpecificationToggleButton.setMaxWidth(Double.MAX_VALUE);
        campaignSpecificationToggleButton.setAlignment(Pos.CENTER_LEFT);
        specificationStatusLabel.getStyleClass().add("spec-status");
        specificationFileNameLabel.getStyleClass().add("compact-value");
        specificationWorksheetNameLabel.getStyleClass().add("compact-value");
        specificationEntriesLabel.getStyleClass().add("compact-value");

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(100);
        ColumnConstraints inputCol = new ColumnConstraints();
        inputCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints actionCol = new ColumnConstraints();
        actionCol.setMinWidth(96);
        grid.getColumnConstraints().addAll(labelCol, inputCol, actionCol);

        grid.add(fileLabel, 0, 0);
        grid.add(specificationFileField, 1, 0);
        grid.add(browseSpecificationButton, 2, 0);
        grid.add(worksheetLabel, 0, 1);
        grid.add(worksheetComboBox, 1, 1);
        grid.add(validateSpecificationButton, 2, 1);

        GridPane loadedGrid = new GridPane();
        loadedGrid.setHgap(10);
        loadedGrid.setVgap(4);
        loadedGrid.getStyleClass().add("spec-summary-grid");
        ColumnConstraints summaryLabelCol = new ColumnConstraints();
        summaryLabelCol.setMinWidth(100);
        ColumnConstraints summaryValueCol = new ColumnConstraints();
        summaryValueCol.setHgrow(Priority.ALWAYS);
        loadedGrid.getColumnConstraints().addAll(summaryLabelCol, summaryValueCol);
        loadedGrid.add(compactLabel("Spreadsheet:"), 0, 0);
        loadedGrid.add(specificationFileNameLabel, 1, 0);
        loadedGrid.add(compactLabel("Worksheet:"), 0, 1);
        loadedGrid.add(specificationWorksheetNameLabel, 1, 1);
        loadedGrid.add(compactLabel("Entries:"), 0, 2);
        loadedGrid.add(specificationEntriesLabel, 1, 2);

        specificationHintLabel.getStyleClass().add("compact-hint");
        campaignSpecificationContent.getChildren().addAll(grid, specificationStatusLabel, loadedGrid, specificationHintLabel);
        campaignSpecificationContent.setVisible(false);
        campaignSpecificationContent.setManaged(false);

        panel.getChildren().addAll(campaignSpecificationToggleButton, campaignSpecificationContent);
        campaignSpecificationToggleButton.setOnAction(event -> setCampaignSpecificationExpanded(!campaignSpecificationExpanded));
        updateCampaignSpecificationSummary();

        browseSpecificationButton.setOnAction(event -> browseCampaignSpecification());
        worksheetComboBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            specificationPendingValidation = campaignSpecificationPath != null;
            selectedCampaignSpecification = null;
            CampaignSpecificationModule.clearActiveSpecification();
            validateSpecificationButton.setDisable(newValue == null || newValue.isBlank());
            updateCampaignSpecificationSummary();
            updateRunButtonState();
        });
        validateSpecificationButton.setOnAction(event -> validateCampaignSpecification());

        return panel;
    }

    private Label compactLabel(final String text) {
        final Label label = new Label(text);
        label.getStyleClass().add("compact-field-label");
        return label;
    }

    private void setCampaignSpecificationExpanded(final boolean expanded) {
        campaignSpecificationExpanded = expanded;
        campaignSpecificationToggleButton.setText((expanded ? "▼ " : "▶ ")
                + "Campaign Specification (Optional)");
        campaignSpecificationContent.setVisible(expanded);
        campaignSpecificationContent.setManaged(expanded);
    }

    private void updateCampaignSpecificationSummary() {
        final String fileName = fileName(campaignSpecificationPath);
        final String worksheetName = worksheetComboBox.getValue() == null
                ? "-"
                : worksheetComboBox.getValue();

        specificationFileField.setText(fileName.isBlank() ? "No spreadsheet selected" : fileName);
        specificationFileNameLabel.setText(fileName.isBlank() ? "No spreadsheet selected" : fileName);
        specificationWorksheetNameLabel.setText(worksheetName == null || worksheetName.isBlank() ? "-" : worksheetName);
        specificationEntriesLabel.setText(selectedCampaignSpecification == null
                ? "-"
                : String.valueOf(selectedCampaignSpecification.entries().size()));

        specificationStatusLabel.getStyleClass().removeAll("spec-status-loaded", "spec-status-warning");
        if (selectedCampaignSpecification != null) {
            specificationStatusLabel.setText("✓ Campaign Specification Loaded");
            specificationStatusLabel.getStyleClass().add("spec-status-loaded");
            specificationHintLabel.setText("");
        } else if (campaignSpecificationPath == null) {
            specificationStatusLabel.setText("No Campaign Specification Selected");
            specificationHintLabel.setText("Campaign validation will be skipped.");
        } else if (specificationPendingValidation) {
            specificationStatusLabel.setText("Worksheet selected. Validate before running the audit.");
            specificationStatusLabel.getStyleClass().add("spec-status-warning");
            specificationHintLabel.setText("");
        } else {
            specificationStatusLabel.setText("Select a worksheet to validate the campaign specification.");
            specificationHintLabel.setText("");
        }
    }

    private VBox createExecutionPanel() {
        VBox panel = new VBox(8);
        panel.getStyleClass().add("compact-section");

        Label title = new Label("EXECUTION");
        title.getStyleClass().add("section-title");

        GridPane actionGrid = new GridPane();
        actionGrid.setHgap(8);
        actionGrid.setVgap(8);

        final List<Button> buttons = List.of(
                runButton,
                openReportButton,
                openSummaryButton,
                openReportsFolderButton);
        for (final Button button : buttons) {
            button.setMaxWidth(Double.MAX_VALUE);
        }

        for (int index = 0; index < buttons.size(); index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(25);
            column.setHgrow(Priority.ALWAYS);
            actionGrid.getColumnConstraints().add(column);
            actionGrid.add(buttons.get(index), index, 0);
        }

        panel.getChildren().addAll(title, actionGrid);
        return panel;
    }

    private void browseCampaignSpecification() {
        final FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Campaign Specification");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Campaign Specification (*.xlsx, *.xlsm)", "*.xlsx", "*.xlsm"));

        final java.io.File selected = chooser.showOpenDialog(root.getScene() == null
                ? null
                : root.getScene().getWindow());
        if (selected == null) {
            return;
        }

        try {
            campaignSpecificationPath = selected.toPath();
            selectedCampaignSpecification = null;
            specificationPendingValidation = true;
            CampaignSpecificationModule.clearActiveSpecification();

            final List<String> worksheetNames =
                    CampaignSpecificationModule.worksheetNames(campaignSpecificationPath);
            worksheetComboBox.getItems().setAll(worksheetNames);
            worksheetComboBox.getSelectionModel().clearSelection();
            worksheetComboBox.setDisable(false);
            validateSpecificationButton.setDisable(true);
            updateCampaignSpecificationSummary();
        } catch (final Exception ex) {
            campaignSpecificationPath = null;
            selectedCampaignSpecification = null;
            specificationPendingValidation = false;
            worksheetComboBox.getItems().clear();
            worksheetComboBox.setDisable(true);
            validateSpecificationButton.setDisable(true);
            specificationFileField.clear();
            updateCampaignSpecificationSummary();
            specificationStatusLabel.setText("Unable to read campaign specification: " + ex.getMessage());
            logArea.appendText(" Campaign specification load failed: " + ex.getMessage() + "\n");
        }

        updateRunButtonState();
        setCampaignSpecificationControlsDisabled(false);
    }

    private void validateCampaignSpecification() {
        final String worksheetName = worksheetComboBox.getValue();
        if (campaignSpecificationPath == null || worksheetName == null || worksheetName.isBlank()) {
            specificationStatusLabel.setText("Select a campaign specification worksheet first.");
            return;
        }

        try {
            selectedCampaignSpecification =
                    CampaignSpecificationModule.load(campaignSpecificationPath, worksheetName);
            CampaignSpecificationModule.setActiveSpecification(selectedCampaignSpecification);
            specificationPendingValidation = false;
            updateCampaignSpecificationSummary();
            logArea.appendText(" Campaign specification validated: "
                    + fileName(selectedCampaignSpecification.sourceFile()) + " / "
                    + selectedCampaignSpecification.worksheetName() + "\n");
        } catch (final Exception ex) {
            selectedCampaignSpecification = null;
            CampaignSpecificationModule.clearActiveSpecification();
            specificationPendingValidation = true;
            updateCampaignSpecificationSummary();
            specificationStatusLabel.setText("Campaign specification validation failed: " + ex.getMessage());
            logArea.appendText(" Campaign specification validation failed: " + ex.getMessage() + "\n");
        }

        updateRunButtonState();
        setCampaignSpecificationControlsDisabled(false);
    }

    private void updateRunButtonState() {
        runButton.setDisable(specificationPendingValidation);
    }

    private void setCampaignSpecificationControlsDisabled(final boolean disabled) {
        browseSpecificationButton.setDisable(disabled);
        worksheetComboBox.setDisable(disabled || campaignSpecificationPath == null);
        validateSpecificationButton.setDisable(disabled
                || campaignSpecificationPath == null
                || worksheetComboBox.getValue() == null
                || worksheetComboBox.getValue().isBlank()
                || !specificationPendingValidation);
    }

    private VBox createCompactPathInfo(String title, String pathValue) {
        VBox box = new VBox(2);
        box.getStyleClass().add("compact-path");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("compact-path-title");
        Label valueLabel = new Label(pathValue);
        valueLabel.getStyleClass().add("compact-path-value");
        box.getChildren().addAll(titleLabel, valueLabel);
        return box;
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

    private static String fileName(final Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }

    public Parent getRoot() {
        return root;
    }

    private void runAudit() {
        if (specificationPendingValidation) {
            logArea.appendText(" Validate the selected campaign specification before running the audit.\n");
            updateRunButtonState();
            return;
        }

        if (selectedCampaignSpecification != null) {
            CampaignSpecificationModule.setActiveSpecification(selectedCampaignSpecification);
        } else {
            CampaignSpecificationModule.clearActiveSpecification();
        }

        ClientContext.setSelectedClient(clientComboBox.getValue());
        runButton.setDisable(true);
        clientComboBox.setDisable(true);
        setCampaignSpecificationControlsDisabled(true);
        openSummaryButton.setDisable(true);
        openReportButton.setDisable(true);
        openReportsFolderButton.setDisable(true);
        logArea.clear();

        AuditTask task = new AuditTask();
        progressBar.progressProperty().bind(task.progressProperty());

        task.messageProperty().addListener((obs, oldVal, newVal) ->
                logArea.appendText("» " + newVal + "\n"));

        task.setOnSucceeded(event -> {
            final AuditOrchestrator.RunSummary summary = task.getValue();
            lastRunSummary   = summary;
            generatedExcelPath = ExcelExporter.outputPath();
            try {
                generatedExcelPath = ExcelExporter.export(summary);
            } catch (final RuntimeException ex) {
                logArea.appendText(" Excel summary generation failed: " + ex.getMessage() + "\n");
            }

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
                            " Total HTML Files : " + summary.totalDiscovered()  + "\n" +
                            " Processed        : " + summary.processed()        + "\n" +
                            " Skipped          : " + summary.skipped()          + "\n" +
                            " Succeeded        : " + summary.succeeded()        + "\n" +
                            " Failed           : " + summary.failed()           + "\n" +
                            " Errors           : " + summary.errored()          + "\n" +
                            " Execution Time   : " + execTime                   + "\n" +
                            "-----------------------------------------\n"    +
                            " Dashboard        : " + dashboardDisplay           + "\n" +
                            " Audit Report     : " + summary.reportPath()       + "\n" +
                            " Summary Sheet    : " + generatedExcelPath         + "\n" +
                            "=========================================\n"
            );

            updateRunButtonState();
            clientComboBox.setDisable(false);
            setCampaignSpecificationControlsDisabled(false);
            openSummaryButton.setDisable(false);
            openReportButton.setDisable(false);
            openReportsFolderButton.setDisable(false);
        });

        task.setOnFailed(event -> {
            updateRunButtonState();
            clientComboBox.setDisable(false);
            setCampaignSpecificationControlsDisabled(false);
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
                logArea.appendText(" Summary workbook has not been generated yet.\n");
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
            Desktop.getDesktop().open(Paths.get("output").toFile());
        } catch (Exception ex) {
            logArea.appendText(" File system lock access path restriction error.\n");
        }
    }
}
