package com.aresstack.argos;

import com.aresstack.argos.camoufox.CamoufoxInstaller;

import java.time.Duration;

/** Browser for the raw-Playwright input tests: -PtestHumanBrowser, else with -PtestCamoufox Argos' managed Camoufox. */
final class HumanBrowserExecutable {
    private HumanBrowserExecutable() {
    }

    static String resolve() {
        String explicit = System.getProperty("argos.testHumanBrowser", "");
        if (!explicit.isBlank() || !Boolean.getBoolean("argos.testCamoufox")) return explicit;
        try {
            ArgosConfig.BrowserInstallation installation = ArgosConfig.BrowserInstallation.fromEnvironment();
            return CamoufoxInstaller.shared(installation.root(), installation.mirror())
                    .awaitExecutable(Duration.ofSeconds(30)).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Managed Camoufox not available under BROWSER_DIR: " + e.getMessage(), e);
        }
    }
}
