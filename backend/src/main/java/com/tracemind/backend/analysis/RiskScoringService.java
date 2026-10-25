package com.tracemind.backend.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class RiskScoringService {

    private static final int HIGH_POINTS = 10;
    private static final int MEDIUM_POINTS = 5;
    private static final int LOW_POINTS = 2;
    private static final int MAX_SCORE = 100;

    private static final String LONG_FILE_RULE = "Long source file";

    public Map<String, Object> calculate(List<Map<String, Object>> findings) {
        int highCount = 0;
        int mediumCount = 0;
        int lowCount = 0;

        if (findings != null) {
            for (Map<String, Object> finding : findings) {
                if (finding == null) {
                    continue;
                }

                // Maintainability advisories must not affect security risk.
                if (LONG_FILE_RULE.equals(finding.get("rule"))) {
                    continue;
                }

                Object value = finding.get("severity");
                String severity = value instanceof String
                        ? ((String) value).toUpperCase(Locale.ROOT)
                        : "";

                switch (severity) {
                    case "HIGH" -> highCount++;
                    case "MEDIUM" -> mediumCount++;
                    case "LOW" -> lowCount++;
                    default -> {
                        // Unknown severities do not contribute to the score.
                    }
                }
            }
        }

        int rawScore = highCount * HIGH_POINTS
                + mediumCount * MEDIUM_POINTS
                + lowCount * LOW_POINTS;

        int score = Math.min(rawScore, MAX_SCORE);

        String riskLevel;
        if (score >= 75) {
            riskLevel = "CRITICAL";
        } else if (score >= 50) {
            riskLevel = "HIGH";
        } else if (score >= 20) {
            riskLevel = "MODERATE";
        } else {
            riskLevel = "LOW";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riskScore", score);
        result.put("riskLevel", riskLevel);
        result.put("severityCounts", Map.of(
                "high", highCount,
                "medium", mediumCount,
                "low", lowCount
        ));

        return result;
    }
}