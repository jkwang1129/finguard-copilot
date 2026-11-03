package com.finguard.copilot.events;

import java.time.*;
import java.util.*;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository.AnalysisView;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.common.error.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalysisEventStreamTest {
    @Test void rejectsInvalidAndFutureCursorsBeforeOpening() {
        var access=mock(GetAnalysisService.class);var events=mock(AnalysisEventRepository.class);var id=UUID.randomUUID();
        when(access.authorize(eq(id),anyString())).thenReturn(new GetAnalysisService.AuthorizedAnalysis("reviewer",new AnalysisView(id,AnalysisStatus.GENERATING,null,null)));
        when(events.head(id)).thenReturn(2L);
        try(var streams=new AnalysisEventStream(access,events,new AnalysisWakeups(),Duration.ofMillis(80),Duration.ofMillis(10))) {
            for(String invalid:List.of("-1","1.2","99999999999999999999999","SECRET\n"))
                assertThatThrownBy(()->streams.open(id,"token",invalid)).isInstanceOfSatisfying(CopilotException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
            assertThatThrownBy(()->streams.open(id,"token","3")).isInstanceOfSatisfying(CopilotException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(streams.active()).isZero();
        }
    }
    @Test void capacityIsPerReviewerAndReclaimedAfterWorkersExit() throws Exception {
        var access=mock(GetAnalysisService.class);var events=mock(AnalysisEventRepository.class);var id=UUID.randomUUID();
        when(access.authorize(eq(id),anyString())).thenReturn(new GetAnalysisService.AuthorizedAnalysis("reviewer",new AnalysisView(id,AnalysisStatus.GENERATING,null,null)));
        when(events.after(eq(id),anyLong(),eq(64))).thenReturn(List.of());
        try(var streams=new AnalysisEventStream(access,events,new AnalysisWakeups(),Duration.ofMillis(200),Duration.ofMillis(10))) {
            for(int i=0;i<4;i++) streams.open(id,"token","0");
            assertThatThrownBy(()->streams.open(id,"token","0")).isInstanceOfSatisfying(CopilotException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
            for(int i=0;i<50 && streams.active()!=0;i++) Thread.sleep(10);
            assertThat(streams.active()).isZero();
        }
    }
    @Test void terminalReplayCompletesAndDoesNotReadEarlierEvents() throws Exception {
        var access=mock(GetAnalysisService.class);var events=mock(AnalysisEventRepository.class);var id=UUID.randomUUID();
        when(access.authorize(eq(id),anyString())).thenReturn(new GetAnalysisService.AuthorizedAnalysis("reviewer",new AnalysisView(id,AnalysisStatus.SUCCEEDED,null,"{}")));
        when(events.head(id)).thenReturn(2L);
        when(events.after(id,1,64)).thenReturn(List.of(new AnalysisEventRepository.Event(id,"2","STATE_CHANGED",AnalysisStatus.SUCCEEDED,null,true,Instant.now())));
        try(var streams=new AnalysisEventStream(access,events,new AnalysisWakeups(),Duration.ofSeconds(1),Duration.ofMillis(10))) {
            streams.open(id,"token","1");
            for(int i=0;i<50 && streams.active()!=0;i++) Thread.sleep(10);
            assertThat(streams.active()).isZero();verify(events).after(id,1,64);
        }
    }
}
