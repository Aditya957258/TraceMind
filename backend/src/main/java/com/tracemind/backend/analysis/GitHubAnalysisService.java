
package com.tracemind.backend.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitHubAnalysisService {

    private final RestClient restClient;

    public GitHubAnalysisService() {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public Map<String, Object> analyzeRepository(String owner, String repo) {
        try {
            Map<?, ?> metadata = restClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(Map.class);

            Map<?, ?> languages = restClient.get()
                    .uri("/repos/{owner}/{repo}/languages", owner, repo)
                    .retrieve()
                    .body(Map.class);

            if (metadata == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an empty repository response");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", metadata.get("name"));
            result.put("fullName", metadata.get("full_name"));
            result.put("description", metadata.get("description"));
            result.put("htmlUrl", metadata.get("html_url"));
            result.put("defaultBranch", metadata.get("default_branch"));
            result.put("stars", metadata.get("stargazers_count"));
            result.put("forks", metadata.get("forks_count"));
            result.put("openIssues", metadata.get("open_issues_count"));
            result.put("primaryLanguage", metadata.get("language"));
            result.put("languages", languages == null ? Map.of() : languages);

            return result;

        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "GitHub repository not found or not publicly accessible");
            }

            if (ex.getStatusCode().value() == 403
                    || ex.getStatusCode().value() == 429) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "GitHub API rate limit reached or access was denied");
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to retrieve repository data from GitHub");
        }
    }
}
