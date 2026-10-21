package com.tracemind.backend.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void detectsDynamicEvaluationPermissiveAccessAndEmptyExceptionHandler() {
        List<Map<String, Object>> findings = new ArrayList<>();

        String source = """
                eval(userInput);
                http.authorizeHttpRequests().permitAll();
                try { riskyOperation(); } catch (Exception ex) {}
                """;

        service.scanFile("src/App.java", source, findings);

        assertEquals(3, findings.size());

        assertEquals("Dynamic code evaluation", findings.get(0).get("rule"));
        assertEquals("HIGH", findings.get(0).get("severity"));
        assertEquals(1, findings.get(0).get("line"));

        assertEquals("Broad access configuration", findings.get(1).get("rule"));
        assertEquals("MEDIUM", findings.get(1).get("severity"));
        assertEquals(2, findings.get(1).get("line"));

        assertEquals(
                "Potentially empty exception handler",
                findings.get(2).get("rule")
        );
        assertEquals("MEDIUM", findings.get(2).get("severity"));
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

    @Test
    void reportsCredentialLikeValuesInTestsAsMediumSeverity() {
        List<Map<String, Object>> findings = new ArrayList<>();

        service.scanFile(
                "backend/src/test/java/ExampleTest.java",
                "String api_key = \"abcdefgh123456\";",
                findings
        );

        assertEquals(1, findings.size());
        assertEquals("Possible hardcoded credential", findings.get(0).get("rule"));
        assertEquals("MEDIUM", findings.get(0).get("severity"));
        assertTrue(
                String.valueOf(findings.get(0).get("explanation"))
                        .contains("test source file")
        );
    }


    @Test
    void invalidRepositoryOwnerReturnsBadRequest() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze("../invalid", "TraceMind")
        );

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void invalidRepositoryNameReturnsBadRequest() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze("Aditya957258", "invalid/repo")
        );

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void rejectsDotAndDotDotRepositoryNames() {
        for (String invalidName : List.of(".", "..")) {
            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> service.analyze("Aditya957258", invalidName)
            );

            assertEquals(400, exception.getStatusCode().value());
        }
    }

    @Test
    void detectsCaseInsensitiveMarkersAndPythonEmptyHandlers() {
        List<Map<String, Object>> findings = new ArrayList<>();

        service.scanFile(
                "src/example.py",
                """
                # FIXME: REVIEW THIS
                except: pass
                """,
                findings
        );

        assertEquals(2, findings.size());
    }
}
