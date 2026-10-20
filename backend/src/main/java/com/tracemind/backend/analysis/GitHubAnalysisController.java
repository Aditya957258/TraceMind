
package com.tracemind.backend.analysis;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
public class GitHubAnalysisController {

    private final GitHubAnalysisService analysisService;

    public GitHubAnalysisController(GitHubAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/{owner}/{repo}")
    public Map<String, Object> analyzeRepository(
            @PathVariable String owner,
            @PathVariable String repo) {

        return analysisService.analyzeRepository(owner, repo);
    }
}
