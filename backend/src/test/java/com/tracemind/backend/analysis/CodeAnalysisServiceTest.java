package com.tracemind.backend.analysis;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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


    private record ApiFixture(
            CodeAnalysisService service,
            MockRestServiceServer server) {}

    private ApiFixture createApiFixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        return new ApiFixture(
                new CodeAnalysisService(builder, new RiskScoringService()),
                server);
    }

    private void expectMetadata(MockRestServiceServer server, String body) {
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
    void successfulGitHubAnalysisReturnsFindingsAndSkipsUnsupportedFiles() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);

        expectTree(fixture.server(), "main", """
                {
                  "truncated": true,
                  "tree": [
                    {"path":"src/App.java","type":"blob","sha":"blob1","size":20},
                    {"path":"README.md","type":"blob","sha":"readme","size":10},
                    {"path":"node_modules/pkg/index.js","type":"blob","sha":"ignored","size":10},
                    {"path":"src","type":"tree","sha":"directory"}
                  ]
                }
                """);

        fixture.server()
                .expect(requestTo(
                        "https://api.github.com/repos/acme/demo/git/blobs/blob1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"content":"Ly8gVE9ETzogZml4Cg==","encoding":"base64"}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals("acme/demo", result.get("repository"));
        assertEquals("main", result.get("defaultBranch"));
        assertEquals(1, result.get("analyzedFileCount"));
        assertEquals(1, result.get("findingCount"));
        assertEquals(List.of("src/App.java"), result.get("analyzedFiles"));
        assertEquals(true, result.get("truncated"));
        assertNotNull(result.get("riskLevel"));
        assertNotNull(result.get("findings"));

        fixture.server().verify();
    }

    @Test
    void emptyMetadataResponseReturnsBadGateway() {
        ApiFixture fixture = createApiFixture();
        expectMetadata(fixture.server(), "null");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void invalidTreeResponseReturnsBadGateway() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);
        expectTree(fixture.server(), "main", """
                {"truncated":false,"not_tree":[]}
                """);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void mapsGitHubMetadataErrorsToExpectedStatuses() {
        Map<HttpStatus, HttpStatus> cases = Map.of(
                HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND,
                HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY);

        cases.forEach((githubStatus, expectedStatus) -> {
            ApiFixture fixture = createApiFixture();

            fixture.server()
                    .expect(requestTo("https://api.github.com/repos/acme/demo"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(githubStatus)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{}"));

            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> fixture.service().analyze("acme", "demo"));

            assertEquals(expectedStatus, exception.getStatusCode());
            fixture.server().verify();
        });
    }

    @Test
    void mapsConnectionFailuresToBadGateway() {
        ApiFixture fixture = createApiFixture();

        fixture.server()
                .expect(requestTo("https://api.github.com/repos/acme/demo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withException(new IOException("connection reset")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        fixture.server().verify();
    }


    @Test
    void malformedBase64BlobReturnsBadGateway() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);
        expectTree(fixture.server(), "main", """
                {"tree":[
                  {"path":"src/App.java","type":"blob","sha":"bad","size":10}
                ]}
                """);

        fixture.server()
                .expect(requestTo(
                        "https://api.github.com/repos/acme/demo/git/blobs/bad"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"content":"a","encoding":"base64"}
                        """,
                        MediaType.APPLICATION_JSON));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertTrue(exception.getReason().contains("could not be decoded"));
        fixture.server().verify();
    }

    @Test
    void skipsOversizedFilesAndBlobsWithoutContent() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);
        expectTree(fixture.server(), "main", """
                {"tree":[
                  {"path":"src/Large.java","type":"blob","sha":"large","size":200001},
                  {"path":"src/Missing.java","type":"blob","sha":"missing","size":10},
                  {"path":"src/NullBlob.java","type":"blob","sha":"nullblob","size":10}
                ]}
                """);

        fixture.server()
                .expect(requestTo(
                        "https://api.github.com/repos/acme/demo/git/blobs/missing"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        fixture.server()
                .expect(requestTo(
                        "https://api.github.com/repos/acme/demo/git/blobs/nullblob"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals(0, result.get("analyzedFileCount"));
        assertEquals(3, result.get("skippedFileCount"));
        assertTrue(((List<?>) result.get("analyzedFiles")).isEmpty());
        fixture.server().verify();
    }

    @Test
    void skipsTreeEntriesThatAreNotBlobsOrSupportedFiles() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);
        expectTree(fixture.server(), "main", """
                {"tree":[
                  "unexpected-entry",
                  {"path":"src","type":"tree","sha":"directory"},
                  {"path":"README.md","type":"blob","sha":"readme","size":20}
                ]}
                """);

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals(0, result.get("analyzedFileCount"));
        assertEquals(0, result.get("skippedFileCount"));
        fixture.server().verify();
    }


    @Test
    void skipsAllExcludedDirectoryPatterns() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);

        expectTree(fixture.server(), "main", """
                {"tree":[
                  {"path":"node_modules/pkg/index.js","type":"blob","sha":"a","size":10},
                  {"path":"vendor/lib/Thing.java","type":"blob","sha":"b","size":10},
                  {"path":".git/Thing.java","type":"blob","sha":"c","size":10},
                  {"path":"build/Thing.java","type":"blob","sha":"d","size":10},
                  {"path":"dist/Thing.java","type":"blob","sha":"e","size":10},
                  {"path":"target/Thing.java","type":"blob","sha":"f","size":10},
                  {"path":".next/Thing.java","type":"blob","sha":"g","size":10}
                ]}
                """);

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals(0, result.get("analyzedFileCount"));
        assertEquals(0, result.get("skippedFileCount"));
        fixture.server().verify();
    }

    @Test
    void recognizesTestPathsAcrossSupportedPathFormats() {
        List<String> testPaths = List.of(
                "test/Example.java",
                "tests/Example.java",
                "backend/src/test/java/Example.java",
                "backend/src/tests/java/Example.java",
                "backend\\\\src\\\\test\\\\java\\\\Example.java"
        );

        for (String testPath : testPaths) {
            List<Map<String, Object>> findings = new ArrayList<>();

            service.scanFile(
                    testPath,
                    "String api_key = \"abcdefgh123456\";",
                    findings
            );

            assertEquals(1, findings.size(), "Path: " + testPath);
            assertEquals(
                    "MEDIUM",
                    findings.get(0).get("severity"),
                    "Path: " + testPath
            );
        }
    }

    @Test
    void nullRepositoryOwnerReturnsBadRequest() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze(null, "demo")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }



    @Test
    void nullTreeResponseReturnsBadGateway() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);
        expectTree(fixture.server(), "main", "null");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().analyze("acme", "demo"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        fixture.server().verify();
    }

    @Test
    void coversRemainingAnalysisBranches() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);

        StringBuilder tree = new StringBuilder("{\"tree\":[");
        for (int i = 0; i < 31; i++) {
            if (i > 0) {
                tree.append(",");
            }
            tree.append(String.format(
                    "{\"path\":\"src/File%d.java\","
                            + "\"type\":\"blob\",\"sha\":\"blob%d\","
                            + "\"size\":10}",
                    i, i));
        }
        tree.append("]}");

        expectTree(fixture.server(), "main", tree.toString());

        String content = java.util.Base64.getEncoder().encodeToString(
                "class A {}".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));

        for (int i = 0; i < 30; i++) {
            final int index = i;
            fixture.server()
                    .expect(requestTo(
                            "https://api.github.com/repos/acme/demo/git/blobs/blob"
                                    + index))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(
                            "{\"content\":\"" + content
                                    + "\",\"encoding\":\"base64\"}",
                            MediaType.APPLICATION_JSON));
        }

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals(30, result.get("analyzedFileCount"));
        assertEquals(1, result.get("skippedFileCount"));
        fixture.server().verify();
    }

    @Test
    void skipsOversizedDecodedBlobAndAcceptsMissingSize() {
        ApiFixture fixture = createApiFixture();

        expectMetadata(fixture.server(), """
                {"full_name":"acme/demo","default_branch":"main"}
                """);

        expectTree(fixture.server(), "main", """
                {"tree":[
                  {"path":"src/NoSize.java","type":"blob","sha":"nosize"},
                  {"path":"src/DecodedLarge.java","type":"blob",
                   "sha":"decodedlarge","size":10}
                ]}
                """);

        String smallContent = java.util.Base64.getEncoder().encodeToString(
                "class A {}".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));

        String oversizedContent = java.util.Base64.getEncoder().encodeToString(
                new byte[200001]);

        fixture.server()
                .expect(requestTo(
                        "https://api.github.com/repos/acme/demo/git/blobs/nosize"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"content\":\"" + smallContent
                                + "\",\"encoding\":\"base64\"}",
                        MediaType.APPLICATION_JSON));

        fixture.server()
                .expect(requestTo(
                        "https://api.github.com/repos/acme/demo/git/blobs/decodedlarge"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"content\":\"" + oversizedContent
                                + "\",\"encoding\":\"base64\"}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result =
                fixture.service().analyze("acme", "demo");

        assertEquals(1, result.get("analyzedFileCount"));
        assertEquals(1, result.get("skippedFileCount"));
        assertEquals(
                List.of("src/NoSize.java"),
                result.get("analyzedFiles"));
        fixture.server().verify();
    }

    @Test
    void detectsRemainingDebugAndBroadAccessAlternatives() {
        List<Map<String, Object>> findings = new ArrayList<>();

        service.scanFile(
                "src/App.java",
                """
                System.out.println("debug");
                exception.printStackTrace();
                http.authorizeHttpRequests().allowAll();
                """,
                findings);

        assertEquals(3, findings.size());
        assertEquals("Debug output", findings.get(0).get("rule"));
        assertEquals(1, findings.get(0).get("line"));
        assertEquals("Debug output", findings.get(1).get("rule"));
        assertEquals(2, findings.get(1).get("line"));
        assertEquals(
                "Broad access configuration",
                findings.get(2).get("rule"));
        assertEquals("MEDIUM", findings.get(2).get("severity"));
    }

}
