package com.finguard.copilot.agent.qualify;

import java.util.*;

/** Source-relevance metrics; errors and unexecuted cases always remain failures. */
final class SemanticMarginQualityMetrics {
    static final String ALGORITHM="single-source-margin-grid-v1";
    static final List<Double> FLOORS=List.of(.75,.80,.825,.85,.875);
    static final List<Double> MARGINS=List.of(0d,.005,.01,.015,.02,.03,.04,.05);
    record Query(String caseId,String query,List<String> expected,List<String> forbidden,String language) {
        Query { expected=List.copyOf(expected);forbidden=List.copyOf(forbidden); }
    }
    record Candidate(String documentId,double score) {
        Candidate { if(documentId==null || documentId.isBlank() || !Double.isFinite(score) || score < -1 || score>1) throw new IllegalArgumentException("Invalid candidate"); }
    }
    record Raw(Query query,List<Candidate> candidates) { Raw { candidates=List.copyOf(candidates); } }
    record Outcome(String caseId,String state,List<String> documentIds,String failureType) {
        Outcome { documentIds=List.copyOf(documentIds);if(!Set.of("EXECUTED","ERROR","NOT_RUN").contains(state) || documentIds.size()>1 || (!state.equals("EXECUTED")&&!documentIds.isEmpty())) throw new IllegalArgumentException("Invalid retrieval outcome"); }
        static Outcome executed(String id,List<String> ids) { return new Outcome(id,"EXECUTED",ids,null); }
    }
    record Metrics(int total,int positives,int unsupported,int relevant,int returned,int correctRejections,int pipelineErrors,
                   double recallAt1,double citationPrecision,double noAnswerAccuracy,boolean passed) {}
    record Selection(double minimumScore,double minimumMargin,Metrics metrics) {}
    static Outcome simulate(Raw row,double floor,double margin) {
        var c=row.candidates();
        boolean accepted=!c.isEmpty() && c.get(0).score()>=floor;
        if(margin>0) accepted=accepted && c.size()>=2 && !c.get(0).documentId().equals(c.get(1).documentId())
            && c.get(0).score()-c.get(1).score()>=margin;
        return Outcome.executed(row.query().caseId(),accepted?List.of(c.get(0).documentId()):List.of());
    }
    static Metrics evaluate(List<Query> cases,List<Outcome> outcomes) {
        var queries=new LinkedHashMap<String,Query>();
        for(var q:cases) if(queries.put(q.caseId(),q)!=null) throw new IllegalArgumentException("Duplicate query");
        var actual=new HashMap<String,Outcome>();
        for(var o:outcomes) if(!queries.containsKey(o.caseId()) || actual.put(o.caseId(),o)!=null) throw new IllegalArgumentException("Invalid outcome identity");
        int positive=0,relevant=0,returned=0,rejected=0,errors=0;
        for(var q:cases) {
            boolean p=!q.expected().isEmpty();if(p) positive++;
            var o=actual.get(q.caseId());
            if(o==null || !o.state().equals("EXECUTED")) { errors++;continue; }
            returned+=o.documentIds().size();
            if(p && !o.documentIds().isEmpty() && q.expected().contains(o.documentIds().get(0))) relevant++;
            if(!p && o.documentIds().isEmpty()) rejected++;
        }
        int negative=cases.size()-positive;
        double recall=positive==0?0:(double)relevant/positive,precision=returned==0?0:(double)relevant/returned,na=negative==0?0:(double)rejected/negative;
        return new Metrics(cases.size(),positive,negative,relevant,returned,rejected,errors,recall,precision,na,errors==0&&recall>=.8&&precision>=.9&&na>=.75);
    }
    static Selection select(List<Raw> rows) {
        if(rows.isEmpty()) throw new IllegalArgumentException("Empty development");
        Selection best=null;
        for(double floor:FLOORS) for(double margin:MARGINS) {
            var metrics=evaluate(rows.stream().map(Raw::query).toList(),rows.stream().map(r->simulate(r,floor,margin)).toList());
            var candidate=new Selection(floor,margin,metrics);
            if(best==null || compare(candidate,best)>0) best=candidate;
        }
        return best;
    }
    private static int compare(Selection a,Selection b) {
        int c=Double.compare((a.metrics().recallAt1()+a.metrics().noAnswerAccuracy())/2,(b.metrics().recallAt1()+b.metrics().noAnswerAccuracy())/2);
        if(c==0)c=Double.compare(a.metrics().citationPrecision(),b.metrics().citationPrecision());
        if(c==0)c=Double.compare(a.minimumScore(),b.minimumScore());
        if(c==0)c=Double.compare(a.minimumMargin(),b.minimumMargin());return c;
    }
}
