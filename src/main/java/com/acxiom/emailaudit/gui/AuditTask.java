package com.acxiom.emailaudit.gui;

import com.acxiom.emailaudit.bootstrap.ApplicationLauncher;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import javafx.concurrent.Task;

public class AuditTask extends Task<AuditOrchestrator.RunSummary> {

    @Override
    protected AuditOrchestrator.RunSummary call() throws Exception {

        updateMessage("Starting audit...");

        AuditOrchestrator.RunSummary summary =
                ApplicationLauncher.runAudit();

        updateProgress(100,100);

        updateMessage("Completed");

        return summary;
    }
}