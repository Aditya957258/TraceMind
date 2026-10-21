package com.tracemind.backend.analysis;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/code-analysis")
public class CodeAnalysisController {

    private final CodeAnalysisService codeAnalysisService;

    public CodeAnalysisController(CodeAnalysisService codeAnalysisService) {
        this.codeAnalysisService = codeAnalysisService;
    }

    @GetMapping("/{owner}/{repo}")
    public Map<String, Object> analyzeCode(
            @PathVariable String owner,
            @PathVariable String repo) {

        return codeAnalysisService.analyze(owner, repo);
    }
}
