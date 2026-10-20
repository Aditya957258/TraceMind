
package com.tracemind.backend.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/structure")
public class RepositoryStructureController {

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader(
                    "Accept",
                    "application/vnd.github+json"
            )
            .build();

    @GetMapping("/{owner}/{repo}")
    public Map<String, Object> analyzeStructure(
            @PathVariable String owner,
            @PathVariable String repo
    ) {
        try {
            Map<?, ?> repository = restClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(Map.class);

            if (repository == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an empty repository response"
                );
            }

            Object branchValue = repository.get("default_branch");

            if (!(branchValue instanceof String branch)
                    || branch.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Could not determine the default branch"
                );
            }

            Map<?, ?> treeResponse = restClient.get()
                    .uri(
                            "/repos/{owner}/{repo}/git/trees/{branch}?recursive=1",
                            owner,
                            repo,
                            branch
                    )
                    .retrieve()
                    .body(Map.class);

            if (treeResponse == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an empty file-tree response"
                );
            }

            List<?> tree = treeResponse.get("tree") instanceof List<?> list
                    ? list
                    : List.of();

            List<Map<String, Object>> files = tree.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<?, ?>) item)
                    .map(item -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("path", item.get("path"));
                        entry.put("type", item.get("type"));
                        entry.put("size", item.get("size"));
                        return entry;
                    })
                    .toList();

            long fileCount = files.stream()
                    .filter(file -> "blob".equals(file.get("type")))
                    .count();

            long directoryCount = files.stream()
                    .filter(file -> "tree".equals(file.get("type")))
                    .count();

            Map<String, Object> response = new LinkedHashMap<>();

            response.put("repository", owner + "/" + repo);
            response.put("defaultBranch", branch);
            response.put("totalEntries", files.size());
            response.put("fileCount", fileCount);
            response.put("directoryCount", directoryCount);
            response.put(
                    "truncated",
                    Boolean.TRUE.equals(treeResponse.get("truncated"))
            );
            response.put("summary", buildStructureSummary(files));
            response.put("entries", files);

            return response;

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();

            if (status == 404) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Repository or branch not found, or not publicly accessible"
                );
            }

            if (status == 403 || status == 429) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "GitHub API rate limit reached. Please try again later."
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub returned an error while retrieving repository structure"
            );

        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not connect to GitHub. Please try again later."
            );
        }
    }

    private Map<String, Object> buildStructureSummary(
            List<Map<String, Object>> files
    ) {
        Set<String> paths = new LinkedHashSet<>();
        Set<String> topLevelFolders = new LinkedHashSet<>();
        Set<String> technologies = new LinkedHashSet<>();
        Set<String> detectedAreas = new LinkedHashSet<>();
        List<String> importantFiles = new ArrayList<>();

        for (Map<String, Object> file : files) {
            Object pathValue = file.get("path");

            if (!(pathValue instanceof String path)) {
                continue;
            }

            paths.add(path);

            if ("tree".equals(file.get("type"))
                    && !path.contains("/")) {
                topLevelFolders.add(path);
            }

            if (isImportantFile(path)) {
                importantFiles.add(path);
            }

            detectTechnologies(path, technologies);
        }

        if (paths.stream().anyMatch(path ->
                path.startsWith("frontend/"))) {
            detectedAreas.add("Frontend application");
        }

        if (paths.stream().anyMatch(path ->
                path.startsWith("backend/"))) {
            detectedAreas.add("Backend application");
        }

        if (paths.contains("README.md")) {
            detectedAreas.add("Project documentation");
        }

        String explanation;

        if (detectedAreas.contains("Frontend application")
                && detectedAreas.contains("Backend application")) {
            explanation =
                    "This repository appears to have a separate frontend and backend architecture.";
        } else if (detectedAreas.contains("Frontend application")) {
            explanation =
                    "This repository appears to contain a frontend application.";
        } else if (detectedAreas.contains("Backend application")) {
            explanation =
                    "This repository appears to contain a backend application.";
        } else {
            explanation =
                    "The repository was scanned. Review the detected files to understand its architecture.";
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("topLevelFolders", new ArrayList<>(topLevelFolders));
        summary.put("importantFiles", importantFiles);
        summary.put("detectedTechnologies", new ArrayList<>(technologies));
        summary.put("detectedAreas", new ArrayList<>(detectedAreas));
        summary.put("explanation", explanation);

        return summary;
    }

    private boolean isImportantFile(String path) {
        return path.equals("README.md")
                || path.equals("package.json")
                || path.equals("pom.xml")
                || path.equals("build.gradle")
                || path.equals("build.gradle.kts")
                || path.equals("settings.gradle")
                || path.equals("settings.gradle.kts")
                || path.equals("requirements.txt")
                || path.equals("pyproject.toml")
                || path.equals("Dockerfile")
                || path.equals("docker-compose.yml")
                || path.equals("compose.yaml")
                || path.equals(".gitignore")
                || path.equals("frontend/package.json")
                || path.equals("backend/pom.xml")
                || path.equals("tsconfig.json")
                || path.equals("vite.config.ts")
                || path.equals("vite.config.js");
    }

    private void detectTechnologies(
            String path,
            Set<String> technologies
    ) {
        if (path.endsWith(".tsx") || path.endsWith(".jsx")) {
            technologies.add("React");
        }

        if (path.endsWith(".ts") || path.endsWith(".tsx")) {
            technologies.add("TypeScript");
        }

        if (path.endsWith(".js") || path.endsWith(".jsx")) {
            technologies.add("JavaScript");
        }

        if (path.endsWith(".java")) {
            technologies.add("Java");
        }

        if (path.endsWith(".py")) {
            technologies.add("Python");
        }

        if (path.endsWith(".css")) {
            technologies.add("CSS");
        }

        if (path.endsWith(".html")) {
            technologies.add("HTML");
        }

        if (path.endsWith(".sql")) {
            technologies.add("SQL");
        }

        if (path.endsWith("pom.xml")) {
            technologies.add("Maven");
        }

        if (path.endsWith("build.gradle")
                || path.endsWith("build.gradle.kts")) {
            technologies.add("Gradle");
        }

        if (path.endsWith("package.json")) {
            technologies.add("Node.js ecosystem");
        }

        if (path.endsWith("requirements.txt")
                || path.endsWith("pyproject.toml")) {
            technologies.add("Python tooling");
        }

        if (path.endsWith("Dockerfile")
                || path.endsWith("compose.yaml")
                || path.endsWith("docker-compose.yml")) {
            technologies.add("Docker");
        }
    }
}
