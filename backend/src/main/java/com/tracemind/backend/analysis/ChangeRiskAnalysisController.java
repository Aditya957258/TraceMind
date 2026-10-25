package com.tracemind.backend.analysis;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/change-risk")
public class ChangeRiskAnalysisController {

    private final ChangeRiskAnalysisService changeRiskAnalysisService;

    public ChangeRiskAnalysisController(
            ChangeRiskAnalysisService changeRiskAnalysisService) {
        this.changeRiskAnalysisService = changeRiskAnalysisService;
    }

    @GetMapping("/{owner}/{repo}")
    public Map<String, Object> analyzeChange(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam String base,
            @RequestParam String head) {

        return changeRiskAnalysisService.analyze(owner, repo, base, head);
    }
}
