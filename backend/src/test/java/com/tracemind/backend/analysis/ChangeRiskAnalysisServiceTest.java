package com.tracemind.backend.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChangeRiskAnalysisServiceTest {

    private record Fixture(
            ChangeRiskAnalysisService service,
            MockRestServiceServer server) {}

    private Fixture createFixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        return new Fixture(
                new ChangeRiskAnalysisService(
                        builder, new RiskScoringService()),
                server);
    }

    @Test
    void analyzesChangedFilesAndCalculatesRisk() {
        Fixture fixture = createFixture();

        fixture.server().expect(requestTo(
                        "https://api.github.com/repos/acme/demo/compare/main...feature"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status": "ahead",
                          "files": [
                            {
                              "filename": "src/SecurityConfig.java",
                              "status": "modified",
                              "additions": 20,
                              "deletions": 4,
                              "changes": 24
                            },
                            {
                              "filename": "src/App.java",
                              "status": "modified",
                              "additions": 5,
                              "deletions": 2,
                              "changes": 7
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo", "main", "feature");

        assertEquals("acme/demo", result.get("repository"));
        assertEquals("main", result.get("base"));
        assertEquals("feature", result.get("head"));
        assertEquals("ahead", result.get("comparisonStatus"));
        assertEquals(2, result.get("changedFileCount"));
        assertEquals(25, result.get("totalAdditions"));
        assertEquals(6, result.get("totalDeletions"));
        assertEquals(1, result.get("findingCount"));
        assertEquals(10, result.get("riskScore"));
        assertEquals("LOW", result.get("riskLevel"));

        @SuppressWarnings("unchecked")
        var findings =
                (java.util.List<Map<String, Object>>) result.get("findings");

        assertEquals(
                "Security-sensitive file changed",
                findings.get(0).get("rule"));
        assertEquals("HIGH", findings.get(0).get("severity"));

        fixture.server().verify();
    }

    @Test
    void returnsLowRiskWhenNoHeuristicFindingsExist() {
        Fixture fixture = createFixture();

        fixture.server().expect(requestTo(
                        "https://api.github.com/repos/acme/demo/compare/main...feature"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status": "ahead",
                          "files": [
                            {
                              "filename": "src/Calculator.java",
                              "status": "modified",
                              "additions": 3,
                              "deletions": 1,
                              "changes": 4
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo", "main", "feature");

        assertEquals(0, result.get("findingCount"));
        assertEquals(0, result.get("riskScore"));
        assertEquals("LOW", result.get("riskLevel"));
        assertTrue(((java.util.List<?>) result.get("findings")).isEmpty());

        fixture.server().verify();
    }

    @Test
    void rejectsUnsafeComparisonReferencesBeforeCallingGitHub() {
        Fixture fixture = createFixture();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze(
                        "acme", "demo", "main", "../feature"));

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode());

        fixture.server().verify();
    }

    @Test
    void mapsMissingGitHubComparisonsToNotFound() {
        Fixture fixture = createFixture();

        fixture.server().expect(requestTo(
                        "https://api.github.com/repos/acme/demo/compare/main...missing"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze(
                        "acme", "demo", "main", "missing"));

        assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getStatusCode());

        fixture.server().verify();
    }
}
