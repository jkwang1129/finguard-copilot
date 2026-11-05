package com.finguard.copilot.agent.runtime;
import java.math.BigDecimal;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.governance.GovernanceProperties;
public record AgentRuntimeSettings(CopilotProperties.Model model,GovernanceProperties governance,BigDecimal maxAnalysisCny,String nativeBoundVersion) {
    public AgentRuntimeSettings(CopilotProperties.Model model,GovernanceProperties governance,BigDecimal ceiling){this(model,governance,ceiling,null);}
    public AgentRuntimeSettings {
        if(model==null || governance==null || maxAnalysisCny==null || maxAnalysisCny.signum()<=0 || maxAnalysisCny.scale()>8) throw new IllegalArgumentException("Explicit Agent analysis budget required");
        nativeBoundVersion=nativeBoundVersion==null || nativeBoundVersion.isBlank()?null:nativeBoundVersion;
        if(nativeBoundVersion!=null && !DeepseekNativeInputBound.VERSION.equals(nativeBoundVersion))throw new IllegalArgumentException("Unsupported native input-bound version");
    }
}
