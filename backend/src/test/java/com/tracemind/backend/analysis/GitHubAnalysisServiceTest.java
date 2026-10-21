
package com.tracemind.backend.analysis;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubAnalysisServiceTest {

    private record Fixture(
            GitHubAnalysisService service,
            MockRestServiceServer server) {}

    private Fixture createFixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        return new Fixture(new GitHubAnalysisService(builder), server);
    }

    private void expectResponse(
            MockRestServiceServer server,
            String path,
            String body) {
        server.expect(requestTo("https://api.github.com" + path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void mapsRepositoryMetadataAndLanguages() {
        Fixture fixture = createFixture();

        expectResponse(fixture.server(), "/repos/acme/demo", """
                {
                  "name": "demo",
                  "full_name": "acme/demo",
                  "description": "Example repository",
                  "html_url": "https://github.com/acme/demo",
                  "default_branch": "main",
                  "stargazers_count": 12,
                  "forks_count": 3,
                  "open_issues_count": 2,
                  "language": "Java"
                }
                """);

        expectResponse(fixture.server(), "/repos/acme/demo/languages", """
                {"Java": 12000, "JavaScript": 3500}
                """);

        Map<String, Object> result =
                fixture.service().analyzeRepository("acme", "demo");

        assertEquals("demo", result.get("name"));
        assertEquals("acme/demo", result.get("fullName"));
        assertEquals("Example repository", result.get("description"));
        assertEquals("https://github.com/acme/demo", result.get("htmlUrl"));
        assertEquals("main", result.get("defaultBranch"));
        assertEquals(12, result.get("stars"));
        assertEquals(3, result.get("forks"));
        assertEquals(2, result.get("openIssues"));
        assertEquals("Java", result.get("primaryLanguage"));
        assertEquals(Map.of("Java", 12000, "JavaScript", 3500),
                result.get("languages"));

        fixture.server().verify();
    }

    @Test
    void usesEmptyLanguagesWhenGitHubReturnsNull() {
        Fixture fixture = createFixture();

        expectResponse(fixture.server(), "/repos/acme/demo", """
                {"name":"demo","full_name":"acme/demo"}
                """);
        expectResponse(fixture.server(), "/repos/acme/demo/languages", "null");

        Map<String, Object> result =
                fixture.service().analyzeRepository("acme", "demo");

        assertEquals(Map.of(), result.get("languages"));
        fixture.server().verify();
    }

    @Test
    void rejectsEmptyRepositoryMetadata() {
        Fixture fixture = createFixture();

        expectResponse(fixture.server(), "/repos/acme/demo", "null");
        expectResponse(fixture.server(), "/repos/acme/demo/languages", "{}");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyzeRepository("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void mapsGitHubErrorsToExpectedStatuses() {
        Map<HttpStatus, HttpStatus> cases = Map.of(
                HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND,
                HttpStatus.FORBIDDEN, HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE,
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
                    () -> fixture.service().analyzeRepository("acme", "demo"));

            assertEquals(expectedStatus, exception.getStatusCode());
            fixture.server().verify();
        });
    }
}
