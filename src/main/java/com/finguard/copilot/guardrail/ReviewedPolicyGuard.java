package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;

/** Trusted configuration only. No policy conditions are inferred from retrieved/model text. */
public final class ReviewedPolicyGuard {
    private final List<PolicySet> sets;

    public ReviewedPolicyGuard(List<PolicySet> sets) {
        this.sets=List.copyOf(Objects.requireNonNull(sets));
        Set<List<String>> versions=new HashSet<>();
        Set<BundleKey> bundles=new HashSet<>();
        for(var set:this.sets) {
            if(!versions.add(List.of(set.ruleCode(),set.version()))) throw new IllegalArgumentException("duplicate reviewed policy version");
            var bundle=new BundleKey(set.ruleCode(),set.policies().stream().map(Policy::binding).collect(java.util.stream.Collectors.toUnmodifiableSet()));
            if(!bundles.add(bundle)) throw new IllegalArgumentException("ambiguous reviewed policy bundle");
        }
    }

    public Set<String> ruleCodes() {return sets.stream().map(PolicySet::ruleCode).collect(java.util.stream.Collectors.toUnmodifiableSet());}

    public void validate(ReviewTaskContext facts,AnalysisResult result,List<KnowledgeEvidence> evidence) {
        Objects.requireNonNull(facts);Objects.requireNonNull(result);Objects.requireNonNull(evidence);
        if(result.recommendation()==AnalysisRecommendation.INSUFFICIENT_EVIDENCE) return;
        List<PolicySet> matches=sets.stream().filter(set->set.ruleCode().equals(facts.reviewTask().ruleCode())
                && set.currency().equals(facts.transaction().currency()) && matchesBundle(set,evidence)).toList();
        if(matches.size()!=1) throw rejected();
        Set<AnalysisRecommendation> decisions=new HashSet<>();
        for(var policy:matches.get(0).policies()) {
            var binding=policy.binding();
            var item=evidence.stream().filter(e->matches(binding,e)).findFirst().orElseThrow();
            if(result.policyCitations().stream().noneMatch(c->binding.documentId().equals(c.documentId())
                    && binding.documentVersion().equals(c.version()) && binding.sectionPath().equals(c.sourcePath())
                    && item.excerpt().equals(c.excerpt()))) throw rejected();
            boolean matched=policy.conditions().stream().allMatch(condition->condition.matches(facts));
            decisions.add(matched?policy.onMatch():policy.onMiss());
        }
        if(decisions.size()!=1 || !decisions.contains(result.recommendation())) throw rejected();
    }

    private static boolean matchesBundle(PolicySet set,List<KnowledgeEvidence> evidence) {
        return evidence.size()==set.policies().size() && set.policies().stream()
                .allMatch(p->evidence.stream().filter(e->matches(p.binding(),e)).count()==1);
    }
    private static boolean matches(Binding binding,KnowledgeEvidence evidence) {
        var citation=evidence.citation();
        return binding.documentId().equals(citation.documentId()) && binding.documentVersion().equals(citation.documentVersion())
                && binding.sectionPath().equals(citation.sectionPath()) && binding.contentSha256().equals(citation.contentChecksum())
                && binding.contentSha256().equals(hash(evidence.excerpt()));
    }

    public enum Field { AMOUNT,CURRENCY,ACCOUNT_STATUS }
    public enum Operator { EQ,GT,GTE,LT,LTE }
    public record Condition(Field field,Operator operator,String value) {
        public Condition {
            if(field==null || operator==null || value==null || value.isBlank() || value.length()>100)
                throw new IllegalArgumentException("invalid policy condition");
            if(field==Field.AMOUNT) {
                if(!value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) throw new IllegalArgumentException("invalid policy decimal");
                new BigDecimal(value);
            } else if(operator!=Operator.EQ || !value.matches("[A-Z][A-Z0-9_]{0,31}")) {
                throw new IllegalArgumentException("non-numeric conditions support exact equality only");
            }
        }
        boolean matches(ReviewTaskContext facts) {
            if(field!=Field.AMOUNT) return value.equals(field==Field.CURRENCY?facts.transaction().currency():facts.accountSummary().status());
            int order=facts.transaction().amount().compareTo(new BigDecimal(value));
            return switch(operator) {case EQ->order==0;case GT->order>0;case GTE->order>=0;case LT->order<0;case LTE->order<=0;};
        }
    }
    public record Binding(String documentId,String documentVersion,String sectionPath,String contentSha256) {
        public Binding {
            text(documentId);text(documentVersion);text(sectionPath);
            if(contentSha256==null || !contentSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid policy checksum");
        }
    }
    public record Policy(Binding binding,List<Condition> conditions,AnalysisRecommendation onMatch,AnalysisRecommendation onMiss) {
        public Policy {
            Objects.requireNonNull(binding);conditions=List.copyOf(Objects.requireNonNull(conditions));
            if(conditions.size()>10 || onMatch==null || onMiss==null) throw new IllegalArgumentException("invalid reviewed policy");
        }
    }
    public record PolicySet(String version,String ruleCode,String currency,List<Policy> policies) {
        public PolicySet {
            text(version);text(ruleCode);
            if("LARGE_AMOUNT".equals(ruleCode) || currency==null || !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("invalid reviewed rule or reserved Core rule");
            policies=List.copyOf(Objects.requireNonNull(policies));
            if(policies.isEmpty() || policies.size()>10 || policies.stream().map(p->List.of(p.binding().documentId(),p.binding().documentVersion(),p.binding().sectionPath())).distinct().count()!=policies.size())
                throw new IllegalArgumentException("invalid policy bundle members");
        }
    }
    private record BundleKey(String ruleCode,Set<Binding> bindings) {}
    private static void text(String value) {if(value==null || value.isBlank() || value.length()>512) throw new IllegalArgumentException("invalid policy identifier");}
    private static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static CopilotException rejected() {
        return new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,"directional output has no unambiguous reviewed policy authorization");
    }
}
