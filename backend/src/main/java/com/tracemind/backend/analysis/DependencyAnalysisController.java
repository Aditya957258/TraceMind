package com.tracemind.backend.analysis;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dependencies")
public class DependencyAnalysisController {

    private final DependencyAnalysisService dependencyAnalysisService;

    public DependencyAnalysisController(
            DependencyAnalysisService dependencyAnalysisService) {
        this.dependencyAnalysisService = dependencyAnalysisService;
    }

    @GetMapping("/{owner}/{repo}")
    public Map<String, Object> analyzeDependencies(
            @PathVariable String owner,
            @PathVariable String repo) {

        return dependencyAnalysisService.analyze(owner, repo);
    }
}
