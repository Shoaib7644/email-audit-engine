package com.acxiom.emailaudit.gui;

public class AppLauncher {
    public static void main(String[] args) {
        // This diverts the launch logic through a class that doesn't inherit from Application
        AuditApplication.main(args);
    }
}