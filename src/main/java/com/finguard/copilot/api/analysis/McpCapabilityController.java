package com.finguard.copilot.api.analysis;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.agent.mcp.*;
import com.finguard.copilot.agent.tool.ToolResult;
import com.finguard.copilot.common.error.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Local HTTP adapter for the STDIO bridge, not a remote MCP transport or OAuth resource server. */
@RestController
@RequestMapping("/api/copilot/review-analyses/{analysisId}")
@ConditionalOnProperty(prefix="finguard.copilot.agent",name="runtime-enabled",havingValue="true")
public class McpCapabilityController {
    private final McpCapabilityService capabilities;private final McpToolAdapter tools;
    public McpCapabilityController(McpCapabilityService capabilities,McpToolAdapter tools){this.capabilities=capabilities;this.tools=tools;}
    @PostMapping("/mcp-capabilities")
    @io.swagger.v3.oas.annotations.Operation(summary="Issue a local five-minute MCP lease",description="Local nonbrowser client only. Body contains only toolScope (one to three shared tool names). Fresh Core role, ownership and facts are rechecked; the random capability is returned once and must not be persisted.",security=@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="CoreReviewerBearer"))
    public ResponseEntity<McpCapabilityService.IssuedCapability> issue(@PathVariable UUID analysisId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false)String authorization,
            @RequestBody JsonNode body,HttpServletRequest request){
        local(request);String token=ReviewAnalysisController.bearerToken(authorization);
        if(body==null || !body.isObject() || body.size()!=1 || !body.path("toolScope").isArray()
            || body.path("toolScope").size()<1 || body.path("toolScope").size()>3)throw invalid();
        List<String> scope=new ArrayList<>();for(var item:body.path("toolScope")){if(!item.isTextual())throw invalid();scope.add(item.asText());}
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(capabilities.issue(analysisId,scope,token));
    }
    @DeleteMapping("/mcp-capabilities")
    @io.swagger.v3.oas.annotations.Operation(summary="Revoke this reviewer's local MCP leases",security=@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="CoreReviewerBearer"))
    public ResponseEntity<Void> revoke(@PathVariable UUID analysisId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false)String authorization,HttpServletRequest request){
        local(request);capabilities.revoke(analysisId,ReviewAnalysisController.bearerToken(authorization));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/mcp-tools")
    @io.swagger.v3.oas.annotations.Operation(summary="List capability-scoped tools for the local STDIO bridge",description="Uses an MCP capability, not a Core JWT. Waiting or terminal frozen revision only.",security=@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="McpCapability"))
    public ResponseEntity<List<JsonNode>> list(@PathVariable UUID analysisId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false)String authorization,HttpServletRequest request){
        local(request);var session=capabilities.authorize(analysisId,ReviewAnalysisController.bearerToken(authorization));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tools.list(session));
    }
    @PostMapping("/mcp-tools")
    @io.swagger.v3.oas.annotations.Operation(summary="Execute one shared read-only tool for the local STDIO bridge",description="Body contains only toolName and arguments; Idempotency-Key is required. Counts against the same four slots as the Graph. This is an internal local adapter, not remote HTTP MCP.",security=@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="McpCapability"))
    public ResponseEntity<ToolResult> call(@PathVariable UUID analysisId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false)String authorization,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body,HttpServletRequest request){
        local(request);var session=capabilities.authorize(analysisId,ReviewAnalysisController.bearerToken(authorization));
        if(body==null || !body.isObject() || body.size()!=2 || !body.path("toolName").isTextual() || !body.path("arguments").isObject())throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tools.call(session,body.path("toolName").asText(),body.path("arguments"),key));
    }
    private static void local(HttpServletRequest request){
        if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()) || request.getHeader("Origin")!=null)
            throw new CopilotException(ErrorCode.FORBIDDEN,"MCP bridge requires a local nonbrowser client");
    }
    private static CopilotException invalid(){return new CopilotException(ErrorCode.INVALID_REQUEST,"Invalid MCP adapter request");}
}
