package com.tracemind.backend.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChangeRiskAnalysisService {

    private static final Pattern REPOSITORY_NAME =
            Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private static final Pattern SAFE_REF =
            Pattern.compile("[A-Za-z0-9_./-]{1,200}");

    private final RestClient github;
    private final RiskScoringService riskScoringService;

    public ChangeRiskAnalysisService() {
        this(RestClient.builder(), new RiskScoringService());
    }

    @Autowired
    public ChangeRiskAnalysisService(
            RiskScoringService riskScoringService) {
        this(RestClient.builder(), riskScoringService);
    }

    ChangeRiskAnalysisService(
            RestClient.Builder builder,
            RiskScoringService riskScoringService) {
        this.riskScoringService = riskScoringService;
        this.github = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public Map<String, Object> analyze(
            String owner, String repo, String base, String head) {

        validateName(owner, "owner");
        validateName(repo, "repository");
        validateRef(base, "base");
        validateRef(head, "head");

        try {
            Map<?, ?> comparison = github.get()
                    .uri("/repos/{owner}/{repo}/compare/{base}...{head}",
                            owner, repo, base, head)
                    .retrieve()
                    .body(Map.class);

            if (comparison == null
                    || !(comparison.get("files") instanceof List<?> files)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an invalid commit comparison.");
            }

            List<Map<String, Object>> changedFiles = new ArrayList<>();
            List<Map<String, Object>> findings = new ArrayList<>();
            int totalAdditions = 0;
            int totalDeletions = 0;

            for (Object item : files) {
                if (!(item instanceof Map<?, ?> file)
                        || !(file.get("filename") instanceof String path)) {
                    continue;
                }

                int additions = number(file.get("additions"));
                int deletions = number(file.get("deletions"));
                int changes = number(file.get("changes"));
                String status = String.valueOf(file.get("status"));

                totalAdditions += additions;
                totalDeletions += deletions;

                Map<String, Object> changed = new LinkedHashMap<>();
                changed.put("file", path);
                changed.put("status", status);
                changed.put("additions", additions);
                changed.put("deletions", deletions);
                changed.put("changes", changes);
                changedFiles.add(changed);

                classifyChange(path, status, changes, findings);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("repository", owner + "/" + repo);
            result.put("base", base);
            result.put("head", head);
            result.put("comparisonStatus", comparison.get("status"));
            result.put("changedFileCount", changedFiles.size());
            result.put("totalAdditions", totalAdditions);
            result.put("totalDeletions", totalDeletions);
            result.put("changedFiles", changedFiles);
            result.put("findingCount", findings.size());
            result.putAll(riskScoringService.calculate(findings));
            result.put("findings", findings);
            result.put(
                    "note",
                    "Heuristic change-risk assessment. Review findings before merging."
            );
            return result;

        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();

            if (code == 404) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Repository or comparison reference not found, or not publicly accessible.");
            }

            if (code == 403 || code == 429) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "GitHub API rate limit or access restriction reached.");
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub commit comparison failed.");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to connect to GitHub.");
        }
    }

    private void classifyChange(
            String path,
            String status,
            int changes,
            List<Map<String, Object>> findings) {

        String lower = path.toLowerCase(Locale.ROOT);
        String severity = null;
        String rule = null;
        String explanation = null;
        String recommendation = null;

        if (lower.matches(".*(^|/)(security|auth|authentication|authorization)(/|\\.|$).*")
                || lower.matches(".*(securityconfig|securityfilter|authconfig).*")) {
            severity = "HIGH";
            rule = "Security-sensitive file changed";
            explanation = "The changed path appears related to authentication, authorization, or security.";
            recommendation = "Review access-control behavior and add or run security-focused tests.";
        } else if (lower.startsWith(".github/workflows/")
                || lower.contains("/.github/workflows/")) {
            severity = "MEDIUM";
            rule = "CI workflow changed";
            explanation = "Changes to CI workflows can alter build, test, or release automation.";
            recommendation = "Review workflow permissions and commands, then run the affected CI pipeline.";
        } else if (lower.matches(".*(^|/)(application|application-[^/]+)\\.(properties|yml|yaml)$")
                || lower.endsWith(".env")
                || lower.endsWith(".env.example")
                || lower.contains("dockerfile")
                || lower.endsWith("pom.xml")
                || lower.endsWith("package.json")
                || lower.endsWith("build.gradle")
                || lower.endsWith("build.gradle.kts")
                || lower.endsWith("settings.gradle")
                || lower.endsWith("settings.gradle.kts")) {
            severity = "MEDIUM";
            rule = "Configuration or dependency file changed";
            explanation = "Configuration or dependency changes can affect runtime behavior across the application.";
            recommendation = "Review configuration differences and run relevant integration or build checks.";
        } else if ("removed".equalsIgnoreCase(status)) {
            severity = "MEDIUM";
            rule = "File removed";
            explanation = "Removing a file may break references or functionality elsewhere in the repository.";
            recommendation = "Check references and run tests covering the removed file's responsibilities.";
        } else if (changes >= 300) {
            severity = "MEDIUM";
            rule = "Large change";
            explanation = "This file contains a large number of changed lines, increasing review complexity.";
            recommendation = "Review the change in smaller logical sections and ensure relevant tests cover it.";
        }

        if (severity == null) {
            return;
        }

        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("file", path);
        finding.put("severity", severity);
        finding.put("rule", rule);
        finding.put("explanation", explanation);
        finding.put("recommendation", recommendation);
        findings.add(finding);
    }

    private int number(Object value) {
        return value instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }

    private void validateName(String value, String label) {
        if (value == null
                || !REPOSITORY_NAME.matcher(value).matches()
                || ".".equals(value)
                || "..".equals(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid GitHub " + label + " name.");
        }
    }

    private void validateRef(String value, String label) {
        if (value == null
                || !SAFE_REF.matcher(value).matches()
                || value.startsWith("/")
                || value.endsWith("/")
                || value.contains("..")
                || value.contains("//")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid comparison " + label + " reference.");
        }
    }
}
