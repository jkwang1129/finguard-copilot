package com.finguard.copilot.agent.qualify;

import java.util.*;

/** Evaluation-only metrics: expected ids are never sent to embedding or chat models. */
final class SemanticQualityMetrics {
    record Observation(String caseId,List<String> expectedDocumentIds,String actualDocumentId,double score) {
        Observation {
            if(caseId==null || caseId.isBlank() || expectedDocumentIds==null || expectedDocumentIds.size()>1
                || expectedDocumentIds.stream().anyMatch(x->x==null || x.isBlank()) || !Double.isFinite(score) || score<-1 || score>1)
                throw new IllegalArgumentException("Invalid quality observation");
            expectedDocumentIds=List.copyOf(expectedDocumentIds);
        }
        boolean accepted(double threshold){return actualDocumentId!=null && score>=threshold;}
        boolean relevant(){return expectedDocumentIds.contains(actualDocumentId);}
    }
    record Metrics(int cases,int positiveCases,int unsupportedCases,int acceptedRetrievals,int relevantRetrievals,
        double recallAt1,double citationPrecision,double noAnswerAccuracy){}

    static Metrics evaluate(List<Observation> rows,double threshold) {
        validate(rows);if(!Double.isFinite(threshold) || threshold<-1 || threshold>1)throw new IllegalArgumentException("Invalid threshold");
        int positives=0,unsupported=0,accepted=0,relevant=0,correctRejections=0;
        for(var row:rows){
            boolean hit=row.accepted(threshold);
            if(hit){accepted++;if(row.relevant())relevant++;}
            if(row.expectedDocumentIds().isEmpty()){unsupported++;if(!hit)correctRejections++;}
            else positives++;
        }
        return new Metrics(rows.size(),positives,unsupported,accepted,relevant,
            positives==0?0:relevant/(double)positives,accepted==0?0:relevant/(double)accepted,
            unsupported==0?0:correctRejections/(double)unsupported);
    }
    static double selectThreshold(List<Observation> development) {
        validate(development);
        if(development.stream().noneMatch(x->x.expectedDocumentIds().isEmpty()) || development.stream().allMatch(x->x.expectedDocumentIds().isEmpty()))
            throw new IllegalArgumentException("Threshold selection needs both positive and unsupported development queries");
        var scores=development.stream().filter(x->x.actualDocumentId()!=null).map(Observation::score).distinct().sorted().toList();
        var candidates=new TreeSet<Double>();candidates.add(-1.0);candidates.add(1.0);
        for(int n=1;n<scores.size();n++)candidates.add((scores.get(n-1)+scores.get(n))/2);
        double best=-1,chosen=-1;
        for(double candidate:candidates){
            var metrics=evaluate(development,candidate);double balanced=(metrics.recallAt1()+metrics.noAnswerAccuracy())/2;
            if(balanced>best+1e-12 || Math.abs(balanced-best)<=1e-12 && candidate>chosen){best=balanced;chosen=candidate;}
        }
        return chosen;
    }
    private static void validate(List<Observation> rows) {
        if(rows==null || rows.isEmpty() || rows.stream().anyMatch(Objects::isNull) || rows.stream().map(Observation::caseId).distinct().count()!=rows.size())
            throw new IllegalArgumentException("Invalid or duplicate quality observations");
    }
}
