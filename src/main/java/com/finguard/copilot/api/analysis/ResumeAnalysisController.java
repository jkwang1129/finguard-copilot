package com.finguard.copilot.api.analysis;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.agent.hitl.*;
import com.finguard.copilot.common.error.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/copilot/review-analyses/{analysisId}/resume")
@ConditionalOnProperty(prefix="finguard.copilot.agent",name="runtime-enabled",havingValue="true")
public class ResumeAnalysisController {
    private final ResumeAnalysisService service;
    public ResumeAnalysisController(ResumeAnalysisService service){this.service=service;}
    @PostMapping
    @io.swagger.v3.oas.annotations.Operation(summary="Continue or cancel a waiting Agent analysis",description="Body contains only expectedRevision (exact int64 string) and action (CONTINUE or CANCEL). Fresh Core authorization and facts are required together with Idempotency-Key. Returns202 for continuation,200 for cancellation; replay does not reset budgets.",security=@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="CoreReviewerBearer"))
    public ResponseEntity<ResumeReceipt> resume(@PathVariable UUID analysisId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false)String authorization,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){
        String token=ReviewAnalysisController.bearerToken(authorization);
        if(body==null || !body.isObject() || body.size()!=2 || !body.path("expectedRevision").isTextual()
            || !body.path("expectedRevision").asText().matches("(0|[1-9][0-9]{0,18})") || !body.path("action").isTextual())throw invalid();
        long revision;ResumeAction action;
        try{revision=Long.parseLong(body.path("expectedRevision").asText());action=ResumeAction.valueOf(body.path("action").asText());}
        catch(IllegalArgumentException exception){throw invalid();}
        var receipt=service.resume(analysisId,revision,action,key,token);
        return ResponseEntity.status(action==ResumeAction.CONTINUE?HttpStatus.ACCEPTED:HttpStatus.OK)
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(receipt);
    }
    private static CopilotException invalid(){return new CopilotException(ErrorCode.INVALID_REQUEST,"Only an exact revision and controlled resume action are accepted");}
}
