package com.tracemind.backend.analysis;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RepositoryStructureControllerTest {

    private record Fixture(
            RepositoryStructureController controller,
            MockRestServiceServer server) {}

    private Fixture createFixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        return new Fixture(
                new RepositoryStructureController(builder),
                server);
    }

    private void expectRepository(
            MockRestServiceServer server, String body) {
        server.expect(requestTo("https://api.github.com/repos/acme/demo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectTree(
            MockRestServiceServer server, String branch, String body) {
        server.expect(requestTo(
                        "https://api.github.com/repos/acme/demo/git/trees/"
                                + branch + "?recursive=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void returnsRepositoryStructureAndDetectsTechnologies() {
        Fixture fixture = createFixture();

        expectRepository(fixture.server(), """
                {
                  "full_name": "acme/demo",
                  "default_branch": "develop"
                }
                """);

        expectTree(fixture.server(), "develop", """
                {
                  "truncated": true,
                  "tree": [
                    {"path":"src","type":"tree","sha":"dir1"},
                    {"path":"src/main","type":"tree","sha":"dir2"},
                    {"path":"src/main/App.jsx","type":"blob","sha":"f1","size":123},
                    {"path":"package.json","type":"blob","sha":"f2","size":80},
                    {"path":"README.md","type":"blob","sha":"f3"},
                    {"path":"src/test/AppTest.java","type":"blob","sha":"f4","size":"unknown"},
                    {"path":"ignored-entry","type":"commit","sha":"f5"},
                    "invalid-entry"
                  ]
                }
                """);

        Map<String, Object> result =
                fixture.controller().getRepositoryStructure("acme", "demo");

        assertEquals("acme/demo", result.get("repository"));
        assertEquals("develop", result.get("defaultBranch"));
        assertEquals(7, result.get("totalEntries"));
        assertEquals(4, result.get("fileCount"));
        assertEquals(2, result.get("directoryCount"));
        assertEquals(true, result.get("truncated"));
        assertNotNull(result.get("summary"));
        assertNotNull(result.get("entries"));

        fixture.server().verify();
    }

    @Test
    void defaultsToMainWhenDefaultBranchIsMissing() {
        Fixture fixture = createFixture();

        expectRepository(fixture.server(), """
                {"full_name":"acme/demo"}
                """);
        expectTree(fixture.server(), "main", """
                {"truncated":false,"tree":[]}
                """);

        Map<String, Object> result =
                fixture.controller().getRepositoryStructure("acme", "demo");

        assertEquals("main", result.get("defaultBranch"));
        assertEquals(0, result.get("totalEntries"));
        assertEquals(0, result.get("fileCount"));
        assertEquals(0, result.get("directoryCount"));

        fixture.server().verify();
    }

    @Test
    void rejectsAnEmptyRepositoryResponse() {
        Fixture fixture = createFixture();
        expectRepository(fixture.server(), "null");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller()
                        .getRepositoryStructure("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void rejectsAnEmptyTreeResponse() {
        Fixture fixture = createFixture();
        expectRepository(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);
        expectTree(fixture.server(), "main", "null");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller()
                        .getRepositoryStructure("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void mapsGitHubErrorStatusesToExpectedApiStatuses() {
        Map<HttpStatus, HttpStatus> cases = Map.of(
                HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND,
                HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY);

        cases.forEach((githubStatus, expectedStatus) -> {
            Fixture fixture = createFixture();

            fixture.server()
                    .expect(requestTo("https://api.github.com/repos/acme/demo"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(githubStatus)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{}"));

            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> fixture.controller()
                            .getRepositoryStructure("acme", "demo"));

            assertEquals(expectedStatus, exception.getStatusCode());
            fixture.server().verify();
        });
    }

    @Test
    void mapsConnectionFailuresToBadGateway() {
        Fixture fixture = createFixture();

        fixture.server()
                .expect(requestTo("https://api.github.com/repos/acme/demo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withException(
                        new IOException("connection reset")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller()
                        .getRepositoryStructure("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void detectsSupportedTechnologiesAndRepositoryAreas() {
        Fixture fixture = createFixture();

        expectRepository(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);

        expectTree(fixture.server(), "main", """
                {
                  "truncated": false,
                  "tree": [
                    {"path":"package.json","type":"blob"},
                    {"path":"src/App.jsx","type":"blob"},
                    {"path":"src/App.tsx","type":"blob"},
                    {"path":"src/index.ts","type":"blob"},
                    {"path":"src/index.js","type":"blob"},
                    {"path":"src/Main.java","type":"blob"},
                    {"path":"pom.xml","type":"blob"},
                    {"path":"build.gradle","type":"blob"},
                    {"path":"build.gradle.kts","type":"blob"},
                    {"path":"app/main.py","type":"blob"},
                    {"path":"requirements.txt","type":"blob"},
                    {"path":"pyproject.toml","type":"blob"},
                    {"path":"src/style.css","type":"blob"},
                    {"path":"index.html","type":"blob"},
                    {"path":"Dockerfile","type":"blob"},
                    {"path":"docker-compose.yml","type":"blob"},
                    {"path":"docker-compose.yaml","type":"blob"},
                    {"path":"src/test/AppTest.java","type":"blob"},
                    {"path":"src/controllers/AppController.java","type":"blob"},
                    {"path":"src/services/AppService.java","type":"blob"},
                    {"path":"src/components/App.jsx","type":"blob"},
                    {"path":"src/config/app.properties","type":"blob"},
                    {"path":"src/models/User.java","type":"blob"},
                    {"path":"src/entities/Order.java","type":"blob"},
                    {"path":"database/schema.sql","type":"blob"},
                    {"path":"migrations/V1.sql","type":"blob"},
                    {"path":"packages/web/src/index.js","type":"blob"},
                    {"path":"test/unit/Test.java","type":"blob"},
                    {"path":"packages/web/tests/unit/Test.java","type":"blob"},
                    {"path":"tests/unit/Test.java","type":"blob"},
                    {"path":"packages/api/controller/HealthController.java","type":"blob"},
                    {"path":"packages/api/service/UserService.java","type":"blob"},
                    {"path":"packages/web/component/Button.jsx","type":"blob"},
                    {"path":"packages/app/configuration/app.properties","type":"blob"},
                    {"path":"packages/app/model/User.java","type":"blob"},
                    {"path":"packages/app/entity/Order.java","type":"blob"},
                    {"path":"packages/app/entities/Invoice.java","type":"blob"},
                    {"path":"packages/db/database/schema.sql","type":"blob"},
                    {"path":"packages/db/migration/V2.sql","type":"blob"},
                    {"path":"packages/db/migrations/V3.sql","type":"blob"},
                    {"path":"migration/V4.sql","type":"blob"}
                  ]
                }
                """);

        Map<String, Object> result =
                fixture.controller().getRepositoryStructure("acme", "demo");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary =
                (Map<String, Object>) result.get("summary");

        @SuppressWarnings("unchecked")
        java.util.List<String> technologies =
                (java.util.List<String>) summary.get("detectedTechnologies");

        @SuppressWarnings("unchecked")
        java.util.List<String> areas =
                (java.util.List<String>) summary.get("detectedAreas");

        assertTrue(technologies.containsAll(java.util.List.of(
                "Node.js / JavaScript",
                "React",
                "TypeScript",
                "JavaScript",
                "Java",
                "Python",
                "CSS",
                "HTML",
                "Docker"
        )));

        assertTrue(areas.containsAll(java.util.List.of(
                "Source code",
                "Testing",
                "API controllers",
                "Business logic and services",
                "UI components",
                "Configuration",
                "Data models",
                "Database and migrations"
        )));

        fixture.server().verify();
    }

    @Test
    void ignoresTreeWhenTreeFieldIsNotAList() {
        Fixture fixture = createFixture();

        expectRepository(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);

        expectTree(fixture.server(), "main", """
                {
                  "truncated": false,
                  "tree": {"path":"src/App.java","type":"blob"}
                }
                """);

        Map<String, Object> result =
                fixture.controller().getRepositoryStructure("acme", "demo");

        assertTrue(((java.util.List<?>) result.get("entries")).isEmpty());
        assertTrue(((Number) result.get("totalEntries")).intValue() == 0);

        fixture.server().verify();
    }
}
