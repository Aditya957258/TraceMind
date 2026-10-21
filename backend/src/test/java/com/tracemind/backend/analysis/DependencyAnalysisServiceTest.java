package com.tracemind.backend.analysis;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DependencyAnalysisServiceTest {

    private record Fixture(
            DependencyAnalysisService service,
            MockRestServiceServer server) {}

    private Fixture createFixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        return new Fixture(new DependencyAnalysisService(builder), server);
    }

    private void expectJson(
            MockRestServiceServer server,
            String path,
            String body) {

        server.expect(requestTo("https://api.github.com" + path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private String blobJson(String source) {
        String encoded = Base64.getEncoder().encodeToString(
                source.getBytes(StandardCharsets.UTF_8));

        return """
                {
                  "encoding": "base64",
                  "content": "%s"
                }
                """.formatted(encoded);
    }

    @Test
    void resolvesRelativeImportsAndSeparatesExternalPackages() {
        Fixture fixture = createFixture();

        expectJson(fixture.server(), "/repos/acme/demo", """
                {
                  "full_name": "acme/demo",
                  "default_branch": "main"
                }
                """);

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/trees/main?recursive=1",
                """
                {
                  "truncated": false,
                  "tree": [
                    {
                      "path": "src/App.tsx",
                      "type": "blob",
                      "sha": "app-sha",
                      "size": 100
                    },
                    {
                      "path": "src/utils.ts",
                      "type": "blob",
                      "sha": "utils-sha",
                      "size": 50
                    }
                  ]
                }
                """
        );

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/app-sha",
                blobJson("""
                        import { helper } from "./utils";
                        import React from "react";
                        """)
        );

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/utils-sha",
                blobJson("export const helper = () => true;")
        );

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals("acme/demo", result.get("repository"));
        assertEquals(2, result.get("analyzedFileCount"));
        assertEquals(0, result.get("unresolvedImportCount"));

        @SuppressWarnings("unchecked")
        var dependencies =
                (java.util.List<Map<String, Object>>) result.get("dependencies");

        assertTrue(dependencies.stream().anyMatch(dependency ->
                "internal".equals(dependency.get("kind"))
                        && "src/utils.ts".equals(dependency.get("target"))));

        assertTrue(dependencies.stream().anyMatch(dependency ->
                "external".equals(dependency.get("kind"))
                        && "react".equals(dependency.get("import"))));

        fixture.server().verify();
    }

    @Test
    void reportsUnresolvedRelativeImportsSeparately() {
        Fixture fixture = createFixture();

        expectJson(fixture.server(), "/repos/acme/demo", """
                {
                  "full_name": "acme/demo",
                  "default_branch": "main"
                }
                """);

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/trees/main?recursive=1",
                """
                {
                  "tree": [
                    {
                      "path": "src/App.ts",
                      "type": "blob",
                      "sha": "app-sha",
                      "size": 100
                    }
                  ]
                }
                """
        );

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/app-sha",
                blobJson("import thing from './missing';")
        );

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals(0, result.get("dependencyCount"));
        assertEquals(1, result.get("unresolvedImportCount"));

        @SuppressWarnings("unchecked")
        var unresolved =
                (java.util.List<Map<String, Object>>) result.get("unresolvedImports");

        assertEquals("./missing", unresolved.get(0).get("import"));
        assertEquals("src/App.ts", unresolved.get(0).get("source"));

        fixture.server().verify();
    }

    @Test
    void rejectsInvalidRepositoryNamesWithoutCallingGitHub() {
        Fixture fixture = createFixture();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze("acme", "../invalid"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void mapsGitHubNotFoundToNotFound() {
        Fixture fixture = createFixture();

        fixture.server()
                .expect(requestTo("https://api.github.com/repos/acme/missing"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze("acme", "missing"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void mapsGitHubRateLimitToTooManyRequests() {
        Fixture fixture = createFixture();

        fixture.server()
                .expect(requestTo("https://api.github.com/repos/acme/demo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze("acme", "demo"));

        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                exception.getStatusCode()
        );

        fixture.server().verify();
    }
}