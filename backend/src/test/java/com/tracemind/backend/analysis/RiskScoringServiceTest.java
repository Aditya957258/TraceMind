package com.tracemind.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskScoringServiceTest {

    private final RiskScoringService service = new RiskScoringService();

    private Map<String, Object> finding(String severity) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", severity);
        return finding;
    }

    @Test
    void cleanRepositoryHasZeroScoreAndLowRisk() {
        Map<String, Object> result = service.calculate(List.of());

        assertEquals(0, result.get("riskScore"));
        assertEquals("LOW", result.get("riskLevel"));
        assertEquals(Map.of("high", 0, "medium", 0, "low", 0),
                result.get("severityCounts"));
    }

    @Test
    void calculatesMixedSeverityScore() {
        Map<String, Object> result = service.calculate(List.of(
                finding("HIGH"),
                finding("MEDIUM"),
                finding("LOW")
        ));

        assertEquals(17, result.get("riskScore"));
        assertEquals("LOW", result.get("riskLevel"));
        assertEquals(Map.of("high", 1, "medium", 1, "low", 1),
                result.get("severityCounts"));
    }

    @Test
    void classifiesModerateHighAndCriticalRisk() {
        assertEquals("MODERATE",
                service.calculate(repeated("MEDIUM", 4)).get("riskLevel"));
        assertEquals("HIGH",
                service.calculate(repeated("HIGH", 5)).get("riskLevel"));
        assertEquals("CRITICAL",
                service.calculate(repeated("HIGH", 8)).get("riskLevel"));
    }

    @Test
    void capsScoreAtOneHundred() {
        Map<String, Object> result = service.calculate(repeated("HIGH", 15));

        assertEquals(100, result.get("riskScore"));
        assertEquals("CRITICAL", result.get("riskLevel"));
    }

    @Test
    void ignoresUnknownSeverityValues() {
        Map<String, Object> result = service.calculate(List.of(
                finding("UNKNOWN"),
                finding(null)
        ));

        assertEquals(0, result.get("riskScore"));
        assertEquals(Map.of("high", 0, "medium", 0, "low", 0),
                result.get("severityCounts"));
    }

    private List<Map<String, Object>> repeated(String severity, int count) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            findings.add(finding(severity));
        }
        return findings;
    }

    @Test
    void classifiesRiskAtExactScoreBoundaries() {
        assertEquals("LOW", service.calculate(
                combine(repeated("HIGH", 1),
                        repeated("MEDIUM", 1),
                        repeated("LOW", 2))
        ).get("riskLevel")); // 19

        assertEquals("MODERATE", service.calculate(
                repeated("MEDIUM", 4)
        ).get("riskLevel")); // 20

        assertEquals("MODERATE", service.calculate(
                combine(repeated("HIGH", 4),
                        repeated("MEDIUM", 1),
                        repeated("LOW", 2))
        ).get("riskLevel")); // 49

        assertEquals("HIGH", service.calculate(
                repeated("HIGH", 5)
        ).get("riskLevel")); // 50

        assertEquals("HIGH", service.calculate(
                combine(repeated("HIGH", 7),
                        repeated("LOW", 2))
        ).get("riskLevel")); // 74

        assertEquals("CRITICAL", service.calculate(
                combine(repeated("HIGH", 7),
                        repeated("MEDIUM", 1))
        ).get("riskLevel")); // 75
    }

    @SafeVarargs
    private final List<Map<String, Object>> combine(
            List<Map<String, Object>>... groups) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (List<Map<String, Object>> group : groups) {
            findings.addAll(group);
        }
        return findings;
    }

}
