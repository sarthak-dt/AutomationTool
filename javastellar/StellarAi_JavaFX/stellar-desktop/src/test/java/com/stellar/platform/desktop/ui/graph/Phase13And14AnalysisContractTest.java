package com.stellar.platform.desktop.ui.graph;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class Phase13And14AnalysisContractTest {
 @Test void engineeringUiExposesRulesAndAdvancedAnalytics() throws Exception {
  String s=Files.readString(Path.of("src/main/java/com/stellar/platform/desktop/ui/graph/EngineeringAnalysisPane.java"));
  assertTrue(s.contains("Diagnostic findings")); assertTrue(s.contains("Advanced analytics"));
  assertTrue(s.contains("setDiagnosticRules")); assertTrue(s.contains("Oscillation (Hz)"));
 }
}
