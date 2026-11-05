package com.finguard.copilot.api.analysis;
import java.util.UUID;
import com.finguard.copilot.agent.graph.AgentTraceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/copilot/review-analyses/{analysisId}/trace")
@ConditionalOnProperty(prefix="finguard.copilot.agent",name="runtime-enabled",havingValue="true")
public class AnalysisTraceController {
    private final AgentTraceService traces;
    public AnalysisTraceController(AgentTraceService traces){this.traces=traces;}
    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary="Read a validated Agent trace without reexecution",security=@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="CoreReviewerBearer"))
    public ResponseEntity<AgentTraceService.Trace> trace(@PathVariable UUID analysisId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false)String authorization){
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
            .body(traces.view(analysisId,ReviewAnalysisController.bearerToken(authorization)));
    }
}
