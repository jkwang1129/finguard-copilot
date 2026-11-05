package com.finguard.copilot.agent.budget;

import java.math.*;
import java.time.Instant;
import java.util.*;
import com.finguard.copilot.usage.ModelCostCalculator.PriceTable;

/** Server-prepared, hash-addressed configuration; contains no Prompt, credential or unvalidated model body. */
public record PreparedAgentCall(String provider,String modelVersion,String promptVersion,String inputSha256,
        String indexVersion,String toolSchemaSha256,int inputUpperBound,int outputCap,PriceTable price,
        BigDecimal maxCallCny,BigDecimal maxAnalysisCny,BigDecimal reviewerDailyCny,
        BigDecimal providerDailyCny,BigDecimal globalDailyCny,Instant priceValidUntil,boolean fixture,String inputBoundVersion,String inputBoundEvidenceSha256,Instant priceValidFrom) {
    public PreparedAgentCall(String provider,String modelVersion,String promptVersion,String inputSha256,String indexVersion,String toolSchemaSha256,int inputUpperBound,int outputCap,PriceTable price,BigDecimal maxCallCny,BigDecimal maxAnalysisCny,BigDecimal reviewerDailyCny,BigDecimal providerDailyCny,BigDecimal globalDailyCny,Instant priceValidUntil,boolean fixture){
        this(provider,modelVersion,promptVersion,inputSha256,indexVersion,toolSchemaSha256,inputUpperBound,outputCap,price,maxCallCny,maxAnalysisCny,reviewerDailyCny,providerDailyCny,globalDailyCny,priceValidUntil,fixture,null,null,null);
    }
    public PreparedAgentCall {
        if((inputBoundVersion==null)!=(inputBoundEvidenceSha256==null) || inputBoundVersion!=null && (fixture || inputBoundVersion.isBlank() || !inputBoundEvidenceSha256.matches("[a-f0-9]{64}") || priceValidFrom==null || priceValidUntil==null || !priceValidUntil.isAfter(priceValidFrom)))throw new IllegalArgumentException("Agent input-bound identity invalid");
        if(!"deepseek".equals(provider) || modelVersion==null || modelVersion.isBlank() || promptVersion==null
            || promptVersion.isBlank() || indexVersion==null || indexVersion.isBlank()
            || inputSha256==null || !inputSha256.matches("[a-f0-9]{64}")
            || toolSchemaSha256==null || !toolSchemaSha256.matches("[a-f0-9]{64}")
            || inputUpperBound<1 || inputUpperBound>32000 || outputCap<1 || outputCap>2000
            || price==null || !"CNY".equals(price.currency()) || priceValidUntil==null) {
            throw new IllegalArgumentException("Agent call configuration invalid");
        }
        for(var limit:List.of(maxCallCny,maxAnalysisCny,reviewerDailyCny,providerDailyCny,globalDailyCny)) {
            if(limit.signum()<=0 || limit.scale()>8 || limit.precision()>20) throw new IllegalArgumentException("Agent budget limit invalid");
        }
        for(var rate:List.of(price.inputPricePerMillion(),price.outputPricePerMillion())) {
            if(rate.scale()>8 || rate.precision()>20 || !fixture && rate.signum()<=0) throw new IllegalArgumentException("Agent price invalid");
        }
    }
    public BigDecimal reserveCny(){
        return price.inputPricePerMillion().multiply(BigDecimal.valueOf(inputUpperBound))
            .add(price.outputPricePerMillion().multiply(BigDecimal.valueOf(outputCap)))
            .divide(BigDecimal.valueOf(1_000_000),8,RoundingMode.CEILING);
    }
    public Map<String,Object> policy(){
        var policy=new TreeMap<String,Object>(Map.ofEntries(Map.entry("provider",provider),Map.entry("model",modelVersion),
            Map.entry("prompt",promptVersion),Map.entry("index",indexVersion),Map.entry("tools",toolSchemaSha256),
            Map.entry("maxCallCny",maxCallCny),Map.entry("maxAnalysisCny",maxAnalysisCny),
            Map.entry("reviewerDailyCny",reviewerDailyCny),Map.entry("providerDailyCny",providerDailyCny),
            Map.entry("globalDailyCny",globalDailyCny),Map.entry("fixture",fixture)));
        if(inputBoundVersion!=null){policy.put("inputBoundVersion",inputBoundVersion);policy.put("inputBoundEvidenceSha256",inputBoundEvidenceSha256);policy.put("price",price);policy.put("priceValidFrom",priceValidFrom);policy.put("priceValidUntil",priceValidUntil);}
        return Collections.unmodifiableMap(policy);
    }
}
