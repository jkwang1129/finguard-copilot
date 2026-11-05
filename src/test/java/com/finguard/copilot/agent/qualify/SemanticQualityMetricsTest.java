package com.finguard.copilot.agent.qualify;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SemanticQualityMetricsTest {
    SemanticQualityMetrics.Observation row(String id,List<String> expected,String actual,double score){return new SemanticQualityMetrics.Observation(id,expected,actual,score);}
    @Test void thresholdUsesDevelopmentPositivesAndNoAnswerCasesTogether(){
        var rows=List.of(row("p1",List.of("a"),"a",.9),row("p2",List.of("b"),"b",.8),row("n1",List.of(),"a",.75));
        double threshold=SemanticQualityMetrics.selectThreshold(rows);
        assertThat(threshold).isGreaterThan(.75).isLessThanOrEqualTo(.8);
        var metrics=SemanticQualityMetrics.evaluate(rows,threshold);
        assertThat(metrics.recallAt1()).isEqualTo(1);
        assertThat(metrics.noAnswerAccuracy()).isEqualTo(1);
        assertThat(metrics.citationPrecision()).isEqualTo(1);
    }
    @Test void wrongTopDocumentAndFalseAnswersStayInTheirDenominators(){
        var rows=List.of(row("p1",List.of("a"),"wrong",.99),row("p2",List.of("b"),"b",.90),row("n1",List.of(),"wrong",.89));
        var metrics=SemanticQualityMetrics.evaluate(rows,.85);
        assertThat(metrics.positiveCases()).isEqualTo(2);
        assertThat(metrics.unsupportedCases()).isEqualTo(1);
        assertThat(metrics.recallAt1()).isEqualTo(.5);
        assertThat(metrics.citationPrecision()).isCloseTo(1.0/3,within(.00001));
        assertThat(metrics.noAnswerAccuracy()).isZero();
    }
    @Test void missingHitsDoNotBecomeCorrectPositiveCases(){
        var rows=List.of(row("p1",List.of("a"),null,-1),row("n1",List.of(),null,-1));
        var metrics=SemanticQualityMetrics.evaluate(rows,-1);
        assertThat(metrics.recallAt1()).isZero();assertThat(metrics.noAnswerAccuracy()).isEqualTo(1);
        assertThat(metrics.acceptedRetrievals()).isZero();
    }
    @Test void invalidScoresDuplicateCasesAndUnbalancedSelectionAreRejected(){
        assertThatThrownBy(()->row("bad",List.of(),"a",Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SemanticQualityMetrics.selectThreshold(List.of(row("p",List.of("a"),"a",.9)))).isInstanceOf(IllegalArgumentException.class);
        var duplicate=List.of(row("p",List.of("a"),"a",.9),row("p",List.of(),"a",.5));
        assertThatThrownBy(()->SemanticQualityMetrics.selectThreshold(duplicate)).isInstanceOf(IllegalArgumentException.class);
    }
}
