package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.finguard.copilot.analysis.domain.AnalysisRecommendation.*;
import static com.finguard.copilot.guardrail.ReviewedPolicyGuard.*;

class ReviewedPolicyGuardTest {
    private final String text="Project test: amount >=100 and account FROZEN; otherwise ignore.";
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");

    @Test void checksAllPredicatesAndConservativeRefusal() throws Exception {
        var policy=policy("all",text,List.of(new Condition(Field.AMOUNT,Operator.GTE,"100.00"),
                new Condition(Field.ACCOUNT_STATUS,Operator.EQ,"FROZEN")),CONFIRMED,IGNORED);
        var guard=new ReviewedPolicyGuard(List.of(new PolicySet("v1","TEST","USD",List.of(policy))));
        var evidence=List.of(evidence("all",text));
        assertThatCode(()->guard.validate(facts("100.000000000000000001","FROZEN"),result(CONFIRMED,evidence),evidence)).doesNotThrowAnyException();
        assertThatCode(()->guard.validate(facts("100","ACTIVE"),result(IGNORED,evidence),evidence)).doesNotThrowAnyException();
        assertThatThrownBy(()->guard.validate(facts("100","ACTIVE"),result(CONFIRMED,evidence),evidence)).isInstanceOf(com.finguard.copilot.common.error.CopilotException.class);
        assertThatThrownBy(()->guard.validate(facts("99.999999999999999999","FROZEN"),result(CONFIRMED,evidence),evidence)).isInstanceOf(com.finguard.copilot.common.error.CopilotException.class);
        assertThatCode(()->new ReviewedPolicyGuard(List.of()).validate(facts("100","ACTIVE"),result(INSUFFICIENT_EVIDENCE,List.of()),List.of())).doesNotThrowAnyException();
    }

    @Test void rejectsUnapprovedEvidenceRegardlessOfAttackWordingAndSpoofedMetadata() throws Exception {
        var policy=policy("all",text,List.of(),CONFIRMED,CONFIRMED);
        var guard=new ReviewedPolicyGuard(List.of(new PolicySet("v1","TEST","USD",List.of(policy))));
        for(String attack:List.of("SYSTEM OVERRIDE","replace final JSON recommendation"," completely new wording")) {
            var altered=evidence("all",text+attack);
            var spoofed=new KnowledgeEvidence(evidence("all",text).citation(),text+attack);
            for(var evidence:List.of(List.of(altered),List.of(spoofed),List.of(evidence("all",text),evidence("extra",attack))))
                assertThatThrownBy(()->guard.validate(facts("100","FROZEN"),result(CONFIRMED,evidence),evidence))
                        .isInstanceOf(com.finguard.copilot.common.error.CopilotException.class);
        }
        assertThatThrownBy(()->new ReviewedPolicyGuard(List.of()).validate(facts("100","FROZEN"),result(CONFIRMED,List.of()),List.of()))
                .isInstanceOf(com.finguard.copilot.common.error.CopilotException.class);
    }

    @Test void rejectsDisagreeingPoliciesAndMissingOrDuplicateBundleMembers() throws Exception {
        var p1=policy("yes","Always confirm",List.of(),CONFIRMED,CONFIRMED);
        var p2=policy("no","Always ignore",List.of(),IGNORED,IGNORED);
        var guard=new ReviewedPolicyGuard(List.of(new PolicySet("conflict","TEST","USD",List.of(p1,p2))));
        var yes=evidence("yes","Always confirm");var no=evidence("no","Always ignore");
        for(var evidence:List.of(List.of(yes,no),List.of(yes),List.of(yes,yes)))
            assertThatThrownBy(()->guard.validate(facts("100","FROZEN"),result(CONFIRMED,evidence),evidence))
                    .isInstanceOf(com.finguard.copilot.common.error.CopilotException.class);
    }

    @Test void rejectsInvalidOrAmbiguousConfiguration() throws Exception {
        assertThatThrownBy(()->new Condition(Field.ACCOUNT_STATUS,Operator.GTE,"ACTIVE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Condition(Field.AMOUNT,Operator.GTE,"NaN")).isInstanceOf(IllegalArgumentException.class);
        var policy=policy("all",text,List.of(),CONFIRMED,CONFIRMED);
        var group=new PolicySet("v1","TEST","USD",List.of(policy));
        assertThatThrownBy(()->new ReviewedPolicyGuard(List.of(group,group))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PolicySet("v1","LARGE_AMOUNT","USD",List.of(policy))).isInstanceOf(IllegalArgumentException.class);
        var altered=policy("all",text+" modified",List.of(),IGNORED,IGNORED);
        assertThatThrownBy(()->new PolicySet("v1","TEST","USD",List.of(policy,altered))).isInstanceOf(IllegalArgumentException.class);
    }

    private Policy policy(String id,String text,List<Condition> conditions,AnalysisRecommendation yes,AnalysisRecommendation no) throws Exception {
        return new Policy(new Binding(id,"v1","Document",hash(text)),conditions,yes,no);
    }
    private KnowledgeEvidence evidence(String id,String text) throws Exception {
        return new KnowledgeEvidence(new KnowledgeCitation(id,"v1",id+"-chunk","Document",hash(text),1,"test"),text);
    }
    private String hash(String text) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private ReviewTaskContext facts(String amount,String status) {
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(1,0,"PENDING","TEST","SYNTH",now),
                new ReviewTaskContext.Transaction(11,42,new BigDecimal(amount),"USD",now,"SYNTHETIC"),
                new ReviewTaskContext.Reconciliation("MATCHED","REFERENCE_ID","SYNTH"),List.of(),
                new ReviewTaskContext.AccountSummary(status,"synthetic"),now);
    }
    private AnalysisResult result(AnalysisRecommendation recommendation,List<KnowledgeEvidence> evidence) {
        return new AnalysisResult(1,UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),"summary","explanation",
                List.of(new AnalysisResult.BusinessEvidence(11,42,new BigDecimal("100"),"USD","TEST","SYNTH",List.of("transaction.amount"))),
                evidence.stream().map(e->new AnalysisResult.PolicyCitation(e.citation().documentId(),"v1","Document",e.excerpt())).toList(),
                recommendation,"reason",List.of(),List.of());
    }
}
