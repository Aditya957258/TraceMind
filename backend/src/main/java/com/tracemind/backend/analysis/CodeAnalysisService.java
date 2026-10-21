package com.tracemind.backend.analysis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CodeAnalysisService {

    private static final int MAX_FILES = 30;
    private static final int MAX_FILE_BYTES = 200_000;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".js", ".jsx", ".ts", ".tsx",
            ".py", ".go", ".php", ".rb", ".cs",
            ".c", ".h", ".cpp", ".hpp"
    );

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|password)"
            + "\\s*[:=]\\s*['\"][^'\"\\s]{8,}['\"]"
    );

    private final RestClient github;
    private final RiskScoringService riskScoringService;

    public CodeAnalysisService() {
        this(new RiskScoringService());
    }

    @Autowired
    public CodeAnalysisService(RiskScoringService riskScoringService) {
        this(RestClient.builder(), riskScoringService);
    }

    public CodeAnalysisService(
            RestClient.Builder githubBuilder,
            RiskScoringService riskScoringService) {
        this.riskScoringService = riskScoringService;
        this.github = githubBuilder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public Map<String, Object> analyze(String owner, String repo) {
        validateRepositoryName(owner, "owner");
        validateRepositoryName(repo, "repository");

        try {
            Map<?, ?> metadata = github.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(Map.class);

            if (metadata == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an empty repository response."
                );
            }

            String branch = String.valueOf(metadata.get("default_branch"));

            Map<?, ?> treeResponse = github.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/git/trees/{branch}")
                            .queryParam("recursive", "1")
                            .build(owner, repo, branch))
                    .retrieve()
                    .body(Map.class);

            if (treeResponse == null
                    || !(treeResponse.get("tree") instanceof List<?> tree)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an invalid repository tree."
                );
            }

            List<Map<String, Object>> findings = new ArrayList<>();
            List<String> analyzedFiles = new ArrayList<>();
            int skippedFiles = 0;

            for (Object item : tree) {
                if (!(item instanceof Map<?, ?> entry)
                        || !"blob".equals(entry.get("type"))) {
                    continue;
                }

                String path = String.valueOf(entry.get("path"));

                if (!isSupported(path)) {
                    continue;
                }

                if (isExcludedPath(path)) {
                    continue;
                }

                if (analyzedFiles.size() >= MAX_FILES) {
                    skippedFiles++;
                    continue;
                }

                Object sizeValue = entry.get("size");

                if (sizeValue instanceof Number size
                        && size.intValue() > MAX_FILE_BYTES) {
                    skippedFiles++;
                    continue;
                }

                String sha = String.valueOf(entry.get("sha"));

                Map<?, ?> blob = github.get()
                        .uri("/repos/{owner}/{repo}/git/blobs/{sha}",
                                owner, repo, sha)
                        .retrieve()
                        .body(Map.class);

                if (blob == null
                        || !(blob.get("content") instanceof String content)) {
                    skippedFiles++;
                    continue;
                }

                byte[] bytes = Base64.getMimeDecoder().decode(content);

                if (bytes.length > MAX_FILE_BYTES) {
                    skippedFiles++;
                    continue;
                }

                String source = new String(bytes, StandardCharsets.UTF_8);
                analyzedFiles.add(path);
                scanFile(path, source, findings);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("repository", metadata.get("full_name"));
            response.put("defaultBranch", branch);
            response.put("analyzedFileCount", analyzedFiles.size());
            response.put("skippedFileCount", skippedFiles);
            response.put("truncated", Boolean.TRUE.equals(
                    treeResponse.get("truncated")));
            response.put("findingCount", findings.size());
            response.putAll(riskScoringService.calculate(findings));
            response.put("findings", findings);
            response.put("analyzedFiles", analyzedFiles);
            response.put(
                    "note",
                    "Heuristic static analysis only. Findings require manual review."
            );

            return response;

        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();

            if (code == 404) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Repository or source file not found, or not publicly accessible."
                );
            }

            if (code == 403 || code == 429) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "GitHub API rate limit or access restriction reached."
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub source retrieval failed."
            );
        } catch (RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to connect to GitHub."
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "A source file could not be decoded."
            );
        }
    }

    void scanFile(
            String path,
            String source,
            List<Map<String, Object>> findings) {

        String[] lines = source.split("\\R", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            String codeLine = stripLineComment(line, path);
            String codeLower = codeLine.toLowerCase(Locale.ROOT);

            if (trimmed.isEmpty()) {
                continue;
            }

            if (lower.contains("todo") || lower.contains("fixme")) {
                addFinding(findings, path, i + 1, "LOW",
                        "TODO/FIXME marker",
                        "This line marks work that may still need attention.",
                        "Confirm whether the task is still outstanding.");
            }

            if (codeLine.contains("System.out.println(")
                    || codeLine.contains("console.log(")
                    || codeLine.contains("printStackTrace()")) {
                addFinding(findings, path, i + 1, "LOW",
                        "Debug output",
                        "Debug output may expose internal details or clutter production logs.",
                        "Use the application's logging framework where appropriate.");
            }

            if (SECRET_PATTERN.matcher(codeLine).find()) {
                boolean testSource = isTestPath(path);
                addFinding(findings, path, i + 1,
                        testSource ? "MEDIUM" : "HIGH",
                        "Possible hardcoded credential",
                        testSource
                                ? "A credential-like value appears in a test source file. It may be a fixture, but verify it is not a real secret."
                                : "A value resembling a credential appears directly in source code. This may be a false positive.",
                        testSource
                                ? "Use an obviously synthetic placeholder and ensure no real credentials are committed."
                                : "Verify the value and move genuine secrets to environment variables or a secret manager.");
            }

            if (codeLower.matches(".*\\beval\\s*\\(.*")) {
                addFinding(findings, path, i + 1, "HIGH",
                        "Dynamic code evaluation",
                        "Evaluating dynamic input as code can create an injection risk.",
                        "Avoid eval where possible and validate untrusted input.");
            }

            if (codeLower.contains("allowall")
                    || codeLower.contains("permitall")) {
                addFinding(findings, path, i + 1, "MEDIUM",
                        "Broad access configuration",
                        "This line may indicate permissive access rules; context determines whether it is risky.",
                        "Review authentication and authorization requirements.");
            }

            if (codeLower.matches(".*catch\\s*\\([^)]*\\)\\s*\\{\\s*}.*")
                    || codeLower.matches(".*except\\s*:\\s*pass.*")) {
                addFinding(findings, path, i + 1, "MEDIUM",
                        "Potentially empty exception handler",
                        "An exception may be swallowed without recovery or useful logging.",
                        "Handle the error explicitly or log it when appropriate.");
            }
        }
    }

    private String stripLineComment(String line, String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        boolean hashComments = lowerPath.endsWith(".py")
                || lowerPath.endsWith(".rb");

        char quote = 0;
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (quote != 0) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
                continue;
            }

            if (hashComments && c == '#') {
                return line.substring(0, i);
            }

            if (!hashComments && c == '/'
                    && i + 1 < line.length()
                    && line.charAt(i + 1) == '/') {
                return line.substring(0, i);
            }
        }

        return line;
    }

    private void addFinding(
            List<Map<String, Object>> findings,
            String path,
            int line,
            String severity,
            String rule,
            String explanation,
            String recommendation) {

        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("file", path);
        finding.put("line", line);
        finding.put("severity", severity);
        finding.put("rule", rule);
        finding.put("explanation", explanation);
        finding.put("recommendation", recommendation);
        findings.add(finding);
    }
    private boolean isTestPath(String path) {
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);

    return normalized.startsWith("test/")
            || normalized.startsWith("tests/")
            || normalized.contains("/test/")
            || normalized.contains("/tests/");
}

private boolean isSupported(String path) {
    String lower = path.toLowerCase(Locale.ROOT);

    return SUPPORTED_EXTENSIONS.stream()
            .anyMatch(extension -> lower.endsWith(extension));
}

private boolean isExcludedPath(String path) {
    String lower = "/" + path.toLowerCase(Locale.ROOT) + "/";

    return lower.contains("/node_modules/")
            || lower.contains("/vendor/")
            || lower.contains("/.git/")
            || lower.contains("/build/")
            || lower.contains("/dist/")
            || lower.contains("/target/")
            || lower.contains("/.next/");
}

    private void validateRepositoryName(String value, String label) {
        if (value == null
                || !value.matches("[A-Za-z0-9_.-]{1,100}")
                || value.equals(".")
                || value.equals("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid GitHub " + label + " name."
            );
        }
    }
}
