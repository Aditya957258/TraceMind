package com.tracemind.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAnalysisServiceTest {

    private final CodeAnalysisService service = new CodeAnalysisService();

    @Test
    void detectsTodoDebugOutputAndPossibleCredentials() {
        List<Map<String, Object>> findings = new ArrayList<>();

        String source = """
                // TODO: finish this method
                console.log("debug message");
                String api_key = "abcdefgh123456";
                """;

        service.scanFile("src/App.java", source, findings);

        assertEquals(3, findings.size());

        assertEquals("TODO/FIXME marker", findings.get(0).get("rule"));
        assertEquals(1, findings.get(0).get("line"));

        assertEquals("Debug output", findings.get(1).get("rule"));
        assertEquals(2, findings.get(1).get("line"));

        assertEquals(
                "Possible hardcoded credential",
                findings.get(2).get("rule")
        );
        assertEquals("HIGH", findings.get(2).get("severity"));
        assertEquals(3, findings.get(2).get("line"));
    }

    @Test
    void cleanSourceProducesNoFindings() {
        List<Map<String, Object>> findings = new ArrayList<>();

        service.scanFile(
                "src/App.java",
                "public class App {\\n    int total = 10;\\n}",
                findings
        );

        assertTrue(findings.isEmpty());
    }
}
