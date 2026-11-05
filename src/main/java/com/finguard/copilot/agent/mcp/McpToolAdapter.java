package com.finguard.copilot.agent.mcp;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.agent.tool.*;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.governance.CanonicalJson;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
@Service
@ConditionalOnProperty(prefix="finguard.copilot.agent",name="runtime-enabled",havingValue="true")
public class McpToolAdapter {
    private final McpCapabilityService capabilities;private final ToolExecutor executor;private final ToolRegistry registry;
    public McpToolAdapter(McpCapabilityService capabilities,ToolExecutor executor,ToolRegistry registry){this.capabilities=capabilities;this.executor=executor;this.registry=registry;}
    public List<JsonNode> list(AuthorizedMcpSession session){
        var valid=capabilities.validate(session);return registry.schemas().stream().filter(t->valid.tools().contains(t.path("name").asText())).toList();
    }
    public ToolResult call(AuthorizedMcpSession session,String name,JsonNode arguments,String key){
        var valid=capabilities.validate(session);
        if(!valid.tools().contains(name))throw new CopilotException(ErrorCode.FORBIDDEN,"MCP tool is outside the granted scope");
        if(key==null || !key.matches("[A-Za-z0-9:_-]{1,100}"))throw new CopilotException(ErrorCode.INVALID_REQUEST,"Invalid MCP invocation key");
        var scope=capabilities.scope(valid);
        return executor.execute(new ToolExecutionContext(valid.analysisId(),valid.reviewer(),scope.snapshotHash(),scope.indexVersion(),
            "mcp-"+CanonicalJson.hash(Map.of("capability",valid.capabilityHash(),"key",key)),valid.revision(),valid.capabilityHash()),new ToolInvocation(name,arguments));
    }
}
