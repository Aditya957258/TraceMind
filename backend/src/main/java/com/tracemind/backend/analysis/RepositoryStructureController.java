package com.tracemind.backend.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/structure")
public class RepositoryStructureController {

    private final RestClient restClient;

    public RepositoryStructureController() {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @GetMapping("/{owner}/{repo}")
    public Map<String, Object> getRepositoryStructure(
            @PathVariable String owner,
            @PathVariable String repo) {

        try {
            Map<?, ?> repository = restClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(Map.class);

            if (repository == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an empty repository response."
                );
            }

            Object branchValue = repository.get("default_branch");

            String defaultBranch = branchValue == null
                    ? "main"
                    : branchValue.toString();

            Map<?, ?> treeResponse = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/git/trees/{branch}")
                            .queryParam("recursive", "1")
                            .build(owner, repo, defaultBranch))
                    .retrieve()
                    .body(Map.class);

            if (treeResponse == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an empty repository tree."
                );
            }

            List<Map<String, Object>> entries = new ArrayList<>();

            Object rawTree = treeResponse.get("tree");

            if (rawTree instanceof List<?> treeItems) {
                for (Object item : treeItems) {
                    if (!(item instanceof Map<?, ?> treeItem)) {
                        continue;
                    }

                    Map<String, Object> entry = new LinkedHashMap<>();

                    entry.put("path", treeItem.get("path"));
                    entry.put("type", treeItem.get("type"));
                    entry.put("sha", treeItem.get("sha"));

                    // Always include size to match the frontend contract.
                    // GitHub does not provide size for every tree entry.
                    Object size = treeItem.get("size");

                    if (size instanceof Number) {
                        entry.put("size", size);
                    } else {
                        entry.put("size", null);
                    }

                    entries.add(entry);
                }
            }

            List<String> topLevelFolders = new ArrayList<>();
            List<String> importantFiles = new ArrayList<>();
            List<String> detectedTechnologies = new ArrayList<>();
            List<String> detectedAreas = new ArrayList<>();

            Set<String> importantFileNames = Set.of(
                    "readme.md",
                    "readme",
                    "package.json",
                    "pom.xml",
                    "build.gradle",
                    "build.gradle.kts",
                    "requirements.txt",
                    "pyproject.toml",
                    "dockerfile",
                    "docker-compose.yml",
                    "docker-compose.yaml",
                    ".gitignore",
                    "application.properties",
                    "application.yml",
                    "vite.config.js",
                    "vite.config.ts",
                    "tsconfig.json"
            );

            int fileCount = 0;
            int directoryCount = 0;

            for (Map<String, Object> entry : entries) {
                String path = String.valueOf(entry.get("path"));
                String type = String.valueOf(entry.get("type"));

                String lowerPath = path.toLowerCase(Locale.ROOT);

                String fileName = lowerPath.substring(
                        lowerPath.lastIndexOf('/') + 1
                );

                if ("tree".equals(type)) {
                    directoryCount++;

                    if (!path.contains("/")) {
                        topLevelFolders.add(path);
                    }
                } else if ("blob".equals(type)) {
                    fileCount++;

                    if (importantFileNames.contains(fileName)) {
                        importantFiles.add(path);
                    }
                }

                detectTechnology(
                        lowerPath,
                        detectedTechnologies,
                        detectedAreas
                );
            }

            Map<String, Object> summary = new LinkedHashMap<>();

            summary.put("topLevelFolders", topLevelFolders);
            summary.put("importantFiles", importantFiles);
            summary.put("detectedTechnologies", detectedTechnologies);
            summary.put("detectedAreas", detectedAreas);

            summary.put(
                    "explanation",
                    buildExplanation(
                            fileCount,
                            directoryCount,
                            detectedTechnologies
                    )
            );

            Map<String, Object> response = new LinkedHashMap<>();

            response.put("repository", repository.get("full_name"));
            response.put("defaultBranch", defaultBranch);
            response.put("totalEntries", entries.size());
            response.put("fileCount", fileCount);
            response.put("directoryCount", directoryCount);

            response.put(
                    "truncated",
                    Boolean.TRUE.equals(treeResponse.get("truncated"))
            );

            response.put("summary", summary);
            response.put("entries", entries);

            return response;

        } catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();

            HttpStatus status = switch (statusCode) {
                case 404 -> HttpStatus.NOT_FOUND;
                case 403, 429 -> HttpStatus.TOO_MANY_REQUESTS;
                default -> HttpStatus.BAD_GATEWAY;
            };

            String message = switch (statusCode) {
                case 404 ->
                        "Repository not found or private. Check the owner and repository name.";

                case 403, 429 ->
                        "GitHub API rate limit or access restriction reached. Try again later.";

                default ->
                        "GitHub API request failed with status "
                                + statusCode + ".";
            };

            throw new ResponseStatusException(
                    status,
                    message,
                    exception
            );

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to connect to GitHub. Please try again later.",
                    exception
            );
        }
    }

    private void detectTechnology(
            String path,
            List<String> technologies,
            List<String> areas) {

        String fileName = path.substring(path.lastIndexOf('/') + 1);

        if (fileName.equals("package.json")) {
            addIfMissing(technologies, "Node.js / JavaScript");
        }

        if (path.endsWith(".tsx") || path.endsWith(".jsx")) {
            addIfMissing(technologies, "React");
        }

        if (path.endsWith(".ts") || path.endsWith(".tsx")) {
            addIfMissing(technologies, "TypeScript");
        }

        if (path.endsWith(".js") || path.endsWith(".jsx")) {
            addIfMissing(technologies, "JavaScript");
        }

        if (path.endsWith(".java")
                || fileName.equals("pom.xml")
                || fileName.equals("build.gradle")
                || fileName.equals("build.gradle.kts")) {
            addIfMissing(technologies, "Java");
        }

        if (path.endsWith(".py")
                || fileName.equals("requirements.txt")
                || fileName.equals("pyproject.toml")) {
            addIfMissing(technologies, "Python");
        }

        if (path.endsWith(".css")) {
            addIfMissing(technologies, "CSS");
        }

        if (path.endsWith(".html")) {
            addIfMissing(technologies, "HTML");
        }

        if (fileName.equals("dockerfile")
                || fileName.equals("docker-compose.yml")
                || fileName.equals("docker-compose.yaml")) {
            addIfMissing(technologies, "Docker");
        }

        if (path.contains("/src/") || path.startsWith("src/")) {
            addIfMissing(areas, "Source code");
        }

        if (path.contains("/test/")
                || path.startsWith("test/")
                || path.contains("/tests/")
                || path.startsWith("tests/")) {
            addIfMissing(areas, "Testing");
        }

        if (path.contains("/controller/")
                || path.contains("/controllers/")) {
            addIfMissing(areas, "API controllers");
        }

        if (path.contains("/service/")
                || path.contains("/services/")) {
            addIfMissing(areas, "Business logic and services");
        }

        if (path.contains("/component/")
                || path.contains("/components/")) {
            addIfMissing(areas, "UI components");
        }

        if (path.contains("/config/")
                || path.contains("/configuration/")) {
            addIfMissing(areas, "Configuration");
        }

        if (path.contains("/model/")
                || path.contains("/models/")
                || path.contains("/entity/")
                || path.contains("/entities/")) {
            addIfMissing(areas, "Data models");
        }

        if (path.contains("/database/")
                || path.contains("/migration/")
                || path.contains("/migrations/")) {
            addIfMissing(areas, "Database and migrations");
        }
    }

    private void addIfMissing(
            List<String> values,
            String value) {

        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private String buildExplanation(
            int fileCount,
            int directoryCount,
            List<String> technologies) {

        StringBuilder explanation = new StringBuilder();

        explanation.append("The repository contains ")
                .append(fileCount)
                .append(" files and ")
                .append(directoryCount)
                .append(" directories in the retrieved GitHub tree.");

        if (technologies.isEmpty()) {
            explanation.append(
                    " No supported technologies could be identified from the file paths."
            );
        } else {
            explanation.append(" Detected technologies include: ")
                    .append(String.join(", ", technologies))
                    .append(".");
        }

        explanation.append(
                " This is a preliminary structure analysis based on repository metadata and file paths, not a full semantic code review."
        );

        return explanation.toString();
    }
}