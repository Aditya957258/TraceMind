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
    void resolvesJavaTypesStaticImportsAndPackageWildcards() {
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
                      "path": "src/main/java/com/acme/service/OrderService.java",
                      "type": "blob",
                      "sha": "service-sha",
                      "size": 100
                    },
                    {
                      "path": "src/main/java/com/acme/model/Order.java",
                      "type": "blob",
                      "sha": "order-sha",
                      "size": 100
                    }
                  ]
                }
                """
        );

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/order-sha",
                blobJson("""
                        package com.acme.model;
                        public class Order {
                            public static final String STATUS = "READY";
                        }
                        """)
        );



        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/service-sha",
                blobJson("""
                        package com.acme.service;
                        import com.acme.model.Order;
                        import static com.acme.model.Order.STATUS;
                        import com.acme.model.*;
                        import java.util.List;
                        public class OrderService {}
                        """)
        );

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        @SuppressWarnings("unchecked")
        var dependencies =
                (java.util.List<Map<String, Object>>) result.get("dependencies");

        assertTrue(dependencies.stream().anyMatch(dependency ->
                "com.acme.model.Order".equals(dependency.get("import"))
                        && "internal".equals(dependency.get("kind"))
                        && "src/main/java/com/acme/model/Order.java"
                                .equals(dependency.get("target"))));

        assertTrue(dependencies.stream().anyMatch(dependency ->
                "com.acme.model.Order.STATUS".equals(dependency.get("import"))
                        && "internal".equals(dependency.get("kind"))
                        && "src/main/java/com/acme/model/Order.java"
                                .equals(dependency.get("target"))));

        assertTrue(dependencies.stream().anyMatch(dependency ->
                "com.acme.model.*".equals(dependency.get("import"))
                        && "internal".equals(dependency.get("kind"))
                        && "src/main/java/com/acme/model/Order.java"
                                .equals(dependency.get("target"))));

        assertTrue(dependencies.stream().anyMatch(dependency ->
                "java.util.List".equals(dependency.get("import"))
                        && "external".equals(dependency.get("kind"))));

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

    @Test
    void resolvesCssImportsWithoutCountingCssAsSourceCode() {
        Fixture fixture = createFixture();

        expectJson(fixture.server(), "/repos/acme/demo",
                "{\"full_name\":\"acme/demo\",\"default_branch\":\"main\"}");

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/trees/main?recursive=1",
                "{\"truncated\":false,\"tree\":["
                + "{\"path\":\"frontend/src/App.tsx\",\"type\":\"blob\",\"sha\":\"app-sha\",\"size\":100},"
                + "{\"path\":\"frontend/src/App.css\",\"type\":\"blob\",\"sha\":\"css-sha\",\"size\":50}"
                + "]}"
        );

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/app-sha",
                blobJson("import './App.css';")
        );

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals(1, result.get("sourceFileCount"));
        assertEquals(1, result.get("analyzedFileCount"));
        assertEquals(1, result.get("dependencyCount"));
        assertEquals(0, result.get("unresolvedImportCount"));

        @SuppressWarnings("unchecked")
        var dependencies =
                (java.util.List<Map<String, Object>>) result.get("dependencies");

        assertTrue(dependencies.stream().anyMatch(dependency ->
                "internal".equals(dependency.get("kind"))
                        && "./App.css".equals(dependency.get("import"))
                        && "frontend/src/App.css".equals(dependency.get("target"))));

        fixture.server().verify();
    }
    @Test
    void calculatesDirectAndTransitiveImpactForDependencyChain() {
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
                      "path": "src/Service.ts",
                      "type": "blob",
                      "sha": "service-sha",
                      "size": 100
                    },
                    {
                      "path": "src/Repository.ts",
                      "type": "blob",
                      "sha": "repository-sha",
                      "size": 100
                    }
                  ]
                }
                """
        );

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/app-sha",
                blobJson("""
                        import { run } from "./Service";
                        export const app = () => run();
                        """)
        );

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/repository-sha",
                blobJson("""
                        export const load = () => "data";
                        """)
        );

        expectJson(
                fixture.server(),
                "/repos/acme/demo/git/blobs/service-sha",
                blobJson("""
                        import { load } from "./Repository";
                        export const run = () => load();
                        """)
        );

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        @SuppressWarnings("unchecked")
        Map<String, Object> impactAnalysis =
                (Map<String, Object>) result.get("impactAnalysis");

        assertTrue(impactAnalysis.containsKey("src/Repository.ts"));

        @SuppressWarnings("unchecked")
        Map<String, Object> repositoryImpact =
                (Map<String, Object>) impactAnalysis.get("src/Repository.ts");

        @SuppressWarnings("unchecked")
        var directDependents =
                (java.util.List<String>) repositoryImpact.get("directDependents");

        @SuppressWarnings("unchecked")
        var transitiveDependents =
                (java.util.List<String>) repositoryImpact.get("transitiveDependents");

        assertEquals(
                java.util.List.of("src/Service.ts"),
                directDependents
        );

        assertEquals(
                java.util.List.of("src/App.tsx"),
                transitiveDependents
        );

        assertEquals(2, repositoryImpact.get("impactCount"));

        fixture.server().verify();
    }

}
