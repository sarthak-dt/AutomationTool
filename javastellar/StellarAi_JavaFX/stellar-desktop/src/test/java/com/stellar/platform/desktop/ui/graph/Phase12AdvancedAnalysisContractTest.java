package com.stellar.platform.desktop.ui.graph;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class Phase12AdvancedAnalysisContractTest {
 @Test void phase12IncludesTimingWindowsBookmarksAndDrilldown() throws Exception {
  String s=Files.readString(Path.of("src/main/java/com/stellar/platform/desktop/ui/graph/EngineeringAnalysisPane.java"));
  assertTrue(s.contains("Lead / lag"));
  assertTrue(s.contains("Event window"));
  assertTrue(s.contains("Add engineering bookmark"));
  assertTrue(s.contains("Drill into waveform"));
  assertTrue(s.contains("timing is observational, not causality"));
 }
}
