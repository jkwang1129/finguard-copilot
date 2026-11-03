package com.finguard.copilot.api.analysis;

import java.util.UUID;
import com.finguard.copilot.analysis.application.AnalysisPageService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/copilot/review-analyses/{analysisId}/view")
public class AnalysisPageController {
    private final AnalysisPageService pages;
    public AnalysisPageController(AnalysisPageService pages){this.pages=pages;}
    @GetMapping public ResponseEntity<AnalysisPageService.Page> get(@PathVariable UUID analysisId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pages.view(analysisId,ReviewAnalysisController.bearerToken(authorization)));
    }
}
