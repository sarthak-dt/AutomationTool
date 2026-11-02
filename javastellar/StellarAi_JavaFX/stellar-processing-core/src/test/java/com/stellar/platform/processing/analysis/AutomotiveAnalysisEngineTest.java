package com.stellar.platform.processing.analysis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomotiveAnalysisEngineTest {
 @Test void ruleDefinitionIsConfigurableAndClampsConfidence(){
  var r=new AutomotiveAnalysisEngine.DiagnosticRule("R","1","test", AutomotiveAnalysisEngine.Severity.HIGH,
    java.util.List.of(new AutomotiveAnalysisEngine.RuleCondition("Speed", AutomotiveAnalysisEngine.Operator.GT,10)),true,2);
  assertEquals(1d,r.confidence()); assertEquals("Speed",r.allConditions().get(0).signalName());
 }
 @Test void emptyInputsAreSafe(){
  assertTrue(AutomotiveAnalysisEngine.extractFeatures(java.util.List.of()).isEmpty());
  assertTrue(AutomotiveAnalysisEngine.evaluateRules(java.util.List.of(),java.util.List.of()).isEmpty());
 }
}
