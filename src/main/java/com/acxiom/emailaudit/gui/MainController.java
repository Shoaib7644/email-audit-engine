package com.acxiom.emailaudit.gui;

import com.acxiom.emailaudit.campaign.CampaignSpecification;
import com.acxiom.emailaudit.campaign.CampaignSpecificationModule;
import com.acxiom.emailaudit.core.ClientContext;
import com.acxiom.emailaudit.core.ExecutionContext;
import com.acxiom.emailaudit.core.ValidationMode;
import com.acxiom.emailaudit.gmail.GmailMetadata;
import com.acxiom.emailaudit.gmail.GmailMetadataContext;
import com.acxiom.emailaudit.gmail.GmailService;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import com.acxiom.emailaudit.reporting.ExcelExporter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.nio.file.Path;
import java.time.Duration;
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

    private final Button runButton              = new Button("Run Pre-Send Audit");
    private final Button openSummaryButton      = new Button("Open Summary Excel");
    private final Button openReportButton       = new Button("Open Dashboard");
    private final Button openReportsFolderButton = new Button("Open Output Folder");
    private final Button postSendRunButton      = new Button("Validate Email");
    private final Button postSendOpenSummaryButton = new Button("Open Summary Excel");
    private final Button postSendOpenReportButton = new Button("Open Dashboard");
    private final Button postSendOpenFolderButton = new Button("Open Output Folder");
    private final TextField postSendInboxField = new TextField("emailenginecheck@gmail.com");
    private final TextField postSendSubjectField = new TextField();
    private final ComboBox<String> postSendFolderComboBox = new ComboBox<>();
    private final ComboBox<String> postSendReceivedWithinComboBox = new ComboBox<>();
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

        VBox clientPanel = createClientPanel();
        VBox campaignSpecificationPanel = createCampaignSpecificationPanel();
        VBox executionPanel = createWorkflowPanel();

        runButton.getStyleClass().add("btn-primary");
        openSummaryButton.getStyleClass().add("btn-secondary");
        openReportButton.getStyleClass().add("btn-secondary");
        openReportsFolderButton.getStyleClass().add("btn-secondary");
        postSendRunButton.getStyleClass().add("btn-primary");
        postSendOpenSummaryButton.getStyleClass().add("btn-secondary");
        postSendOpenReportButton.getStyleClass().add("btn-secondary");
        postSendOpenFolderButton.getStyleClass().add("btn-secondary");

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
        postSendOpenSummaryButton.setDisable(true);
        postSendOpenReportButton.setDisable(true);
        postSendOpenFolderButton.setDisable(true);

        root.getChildren().addAll(
                headerPanel,
                clientPanel,
                campaignSpecificationPanel,
                executionPanel,
                progressBar,
                consoleContainer);

        runButton.setOnAction(e -> runPreSendAudit());
        openReportButton.setOnAction(e -> openDashboardInChrome());
        openSummaryButton.setOnAction(e -> openSummaryExcel());
        openReportsFolderButton.setOnAction(e -> openReportsFolder());
        postSendRunButton.setOnAction(e -> runPostSendAudit());
        postSendOpenReportButton.setOnAction(e -> openDashboardInChrome());
        postSendOpenSummaryButton.setOnAction(e -> openSummaryExcel());
        postSendOpenFolderButton.setOnAction(e -> openReportsFolder());
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

        return new CollapsiblePanel(
                "Campaign Specification (Optional)",
                campaignSpecificationContent,
                false);
    }

    private Label compactLabel(final String text) {
        final Label label = new Label(text);
        label.getStyleClass().add("compact-field-label");
        return label;
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

    private VBox createWorkflowPanel() {
        VBox panel = new VBox(8);
        panel.getChildren().addAll(createPreSendPanel(), createPostSendPanel());
        return panel;
    }

    private VBox createPreSendPanel() {
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

        VBox content = new VBox(8, actionGrid);
        return new CollapsiblePanel("PRE-SEND VALIDATION", content, true);
    }

    private VBox createPostSendPanel() {
        GridPane formGrid = new GridPane();
        formGrid.setHgap(10);
        formGrid.setVgap(6);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(100);
        ColumnConstraints inputCol = new ColumnConstraints();
        inputCol.setHgrow(Priority.ALWAYS);
        formGrid.getColumnConstraints().addAll(labelCol, inputCol);

        postSendSubjectField.setPromptText("Email subject");
        postSendInboxField.setMaxWidth(Double.MAX_VALUE);
        postSendSubjectField.setMaxWidth(Double.MAX_VALUE);
        postSendFolderComboBox.getItems().setAll("Inbox");
        postSendFolderComboBox.getSelectionModel().select("Inbox");
        postSendReceivedWithinComboBox.getItems().setAll("Last 24 Hours", "Last 7 Days");
        postSendReceivedWithinComboBox.getSelectionModel().select("Last 24 Hours");
        postSendFolderComboBox.setMaxWidth(Double.MAX_VALUE);
        postSendReceivedWithinComboBox.setMaxWidth(Double.MAX_VALUE);

        formGrid.add(compactLabel("Inbox:"), 0, 0);
        formGrid.add(postSendInboxField, 1, 0);
        formGrid.add(compactLabel("Subject:"), 0, 1);
        formGrid.add(postSendSubjectField, 1, 1);
        formGrid.add(compactLabel("Folder:"), 0, 2);
        formGrid.add(postSendFolderComboBox, 1, 2);
        formGrid.add(compactLabel("Received Within:"), 0, 3);
        formGrid.add(postSendReceivedWithinComboBox, 1, 3);

        GridPane actionGrid = new GridPane();
        actionGrid.setHgap(8);
        actionGrid.setVgap(8);

        final List<Button> buttons = List.of(
                postSendRunButton,
                postSendOpenReportButton,
                postSendOpenSummaryButton,
                postSendOpenFolderButton);
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

        VBox content = new VBox(8, formGrid, actionGrid);
        return new CollapsiblePanel("POST-SEND VALIDATION", content, false);
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

    private static String fileName(final Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }

    public Parent getRoot() {
        return root;
    }

    private void runPreSendAudit() {
        GmailMetadataContext.clear();
        runValidation(
                ValidationMode.PRE_SEND,
                ExecutionContext.DEFAULT_INPUT_SOURCE);
    }

    private void runPostSendAudit() {
        final String inbox = postSendInboxField.getText() == null
                ? ""
                : postSendInboxField.getText().trim();
        final String subject = postSendSubjectField.getText() == null
                ? ""
                : postSendSubjectField.getText().trim();
        final String folder = postSendFolderComboBox.getValue() == null
                ? "Inbox"
                : postSendFolderComboBox.getValue();
        final String receivedWithin = postSendReceivedWithinComboBox.getValue() == null
                ? "Last 24 Hours"
                : postSendReceivedWithinComboBox.getValue();

        if (subject.isBlank()) {
            logArea.appendText(" Enter an email subject for Post-Send validation.\n");
            return;
        }

        final String inputSource = inbox.isBlank()
                ? "Gmail Inbox"
                : "Gmail Inbox - " + inbox + " / " + folder;

        runValidation(
                ValidationMode.POST_SEND,
                inputSource,
                () -> {
                    final GmailMetadata metadata =
                            new GmailService(inbox).downloadNewestMatchingEmail(
                                    subject,
                                    folder,
                                    receivedWithinDuration(receivedWithin));
                    GmailMetadataContext.set(metadata);
                    return metadata.tempDirectory();
                },
                true);
    }

    private void runValidation(
            final ValidationMode validationMode,
            final String inputSource) {

        runValidation(validationMode, inputSource, null, false);
    }

    private void runValidation(
            final ValidationMode validationMode,
            final String inputSource,
            final AuditTask.InputDirectoryProvider inputDirectoryProvider,
            final boolean cleanupInputDirectory) {

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
        postSendRunButton.setDisable(true);
        clientComboBox.setDisable(true);
        setPostSendControlsDisabled(true);
        setCampaignSpecificationControlsDisabled(true);
        lastRunSummary = null;
        generatedExcelPath = null;
        openSummaryButton.setDisable(true);
        openReportButton.setDisable(true);
        openReportsFolderButton.setDisable(true);
        postSendOpenSummaryButton.setDisable(true);
        postSendOpenReportButton.setDisable(true);
        postSendOpenFolderButton.setDisable(true);
        logArea.clear();
        logArea.appendText("» Validation Mode: " + validationMode.name() + "\n");
        logArea.appendText("» Input Source: " + inputSource + "\n");

        AuditTask task = inputDirectoryProvider == null
                ? new AuditTask(validationMode, inputSource)
                : new AuditTask(validationMode, inputSource, inputDirectoryProvider, cleanupInputDirectory);
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

            final String dashboardDisplay =
                    summary.dashboardPath() != null
                            ? summary.dashboardPath().toString()
                            : "N/A";

            final String completionSummary =
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
                            " Summary Sheet    : " + generatedExcelPath         + "\n" +
                            " Output Folder    : " + ExecutionOutputManager.ensureCurrentExecution().executionRoot() + "\n" +
                            "=========================================\n";
            logArea.appendText(completionSummary);
            ExecutionOutputManager.writeLogs(logArea.getText());
            ExecutionOutputManager.completeExecution(summary, generatedExcelPath, executionStatus(summary));

            updateRunButtonState();
            postSendRunButton.setDisable(false);
            clientComboBox.setDisable(false);
            setPostSendControlsDisabled(false);
            setCampaignSpecificationControlsDisabled(false);
            if (validationMode == ValidationMode.POST_SEND) {
                postSendOpenSummaryButton.setDisable(false);
                postSendOpenReportButton.setDisable(false);
                postSendOpenFolderButton.setDisable(false);
            } else {
                openSummaryButton.setDisable(false);
                openReportButton.setDisable(false);
                openReportsFolderButton.setDisable(false);
            }
        });

        task.setOnFailed(event -> {
            updateRunButtonState();
            postSendRunButton.setDisable(false);
            clientComboBox.setDisable(false);
            setPostSendControlsDisabled(false);
            setCampaignSpecificationControlsDisabled(false);
            logArea.appendText("CRITICAL PIPELINE EXCEPTION: "
                    + task.getException().getMessage() + "\n");
            ExecutionOutputManager.writeLogs(logArea.getText());
            ExecutionOutputManager.completeExecution(null, generatedExcelPath, "ERROR");
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private static Duration receivedWithinDuration(final String label) {
        if ("Last 7 Days".equalsIgnoreCase(label)) {
            return Duration.ofDays(7);
        }
        return Duration.ofHours(24);
    }

    private static String executionStatus(final AuditOrchestrator.RunSummary summary) {
        if (summary.errored() > 0) {
            return "ERROR";
        }
        if (summary.failed() > 0) {
            return "FAIL";
        }
        return "PASS";
    }

    private void setPostSendControlsDisabled(final boolean disabled) {
        postSendInboxField.setDisable(disabled);
        postSendSubjectField.setDisable(disabled);
        postSendFolderComboBox.setDisable(disabled);
        postSendReceivedWithinComboBox.setDisable(disabled);
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

    private void openDashboardInChrome() {
        try {
            if (lastRunSummary == null) return;

            Path dashboardToOpen = lastRunSummary.dashboardPath();

            if (dashboardToOpen == null || !dashboardToOpen.toFile().exists()) {
                logArea.appendText(" Missing visualization engine paths.\n");
                return;
            }

            String        targetUri     = dashboardToOpen.toUri().toString();
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
            Desktop.getDesktop().open(
                    ExecutionOutputManager.ensureCurrentExecution().executionRoot().toFile());
        } catch (Exception ex) {
            logArea.appendText(" File system lock access path restriction error.\n");
        }
    }

    private static final class CollapsiblePanel extends VBox {

        private final String title;
        private final Button toggleButton = new Button();
        private final Node content;
        private boolean expanded;

        private CollapsiblePanel(
                final String title,
                final Node content,
                final boolean expandedByDefault) {

            super(6);
            this.title = title;
            this.content = content;

            getStyleClass().add("compact-section");
            toggleButton.getStyleClass().add("section-toggle");
            toggleButton.setMaxWidth(Double.MAX_VALUE);
            toggleButton.setAlignment(Pos.CENTER_LEFT);
            toggleButton.setOnAction(event -> setExpanded(!expanded));

            getChildren().addAll(toggleButton, content);
            setExpanded(expandedByDefault);
        }

        private void setExpanded(final boolean expanded) {
            this.expanded = expanded;
            toggleButton.setText((expanded ? "▼ " : "▶ ") + title);
            content.setVisible(expanded);
            content.setManaged(expanded);
        }
    }
}
