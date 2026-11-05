package com.finguard.copilot.events;

import java.time.*;
import java.util.*;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository.AnalysisView;
import com.finguard.copilot.api.analysis.AnalysisEventsController;
import com.finguard.copilot.api.error.GlobalExceptionHandler;
import com.finguard.copilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisEventsHttpTest {
    @Test void structuredEventDataCannotInjectSseFramingOrBecomeAViewFragment() throws Exception {
        var id=UUID.randomUUID();var access=mock(GetAnalysisService.class);var events=mock(AnalysisEventRepository.class);
        when(access.authorize(id,"token")).thenReturn(new GetAnalysisService.AuthorizedAnalysis("reviewer",new AnalysisView(id,AnalysisStatus.FAILED,"CONTROLLED",null)));
        when(events.head(id)).thenReturn(1L);
        var event=new AnalysisEventRepository.Event(id,"1","STATE_CHANGED",AnalysisStatus.FAILED,"<xsl:stylesheet>\r\nevent:forged\r\nid:999",false,Instant.now());
        when(events.after(eq(id),anyLong(),eq(64))).thenReturn(List.of(event));
        try(var streams=new AnalysisEventStream(access,events,new AnalysisWakeups(),Duration.ofSeconds(2),Duration.ofMillis(10))){
            var mvc=MockMvcBuilders.standaloneSetup(new AnalysisEventsController(streams)).build();
            var request=mvc.perform(get("/api/copilot/review-analyses/{id}/events",id).header("Authorization","Bearer token")).andReturn();
            request.getAsyncResult(3000);
            String body=mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body.lines().filter(line->line.startsWith("event:")).toList()).containsExactly("event:analysis-state");
            assertThat(body.lines().filter(line->line.startsWith("id:")).toList()).containsExactly("id:1");
            assertThat(body).contains("\\r\\nevent:forged","data:{");
        }
    }
    @Test void authorizationFailureAfterOpeningSendsOnlyControlledError() throws Exception {
        var id=UUID.randomUUID();var access=mock(GetAnalysisService.class);var events=mock(AnalysisEventRepository.class);
        when(access.authorize(id,"token")).thenReturn(new GetAnalysisService.AuthorizedAnalysis("reviewer",new AnalysisView(id,AnalysisStatus.GENERATING,null,null)))
            .thenThrow(new CopilotException(ErrorCode.UNAUTHENTICATED,"secret-token-raw-prompt"));
        when(events.after(eq(id),anyLong(),eq(64))).thenReturn(List.of());
        try(var streams=new AnalysisEventStream(access,events,new AnalysisWakeups(),Duration.ofSeconds(2),Duration.ofMillis(10))) {
            var mvc=MockMvcBuilders.standaloneSetup(new AnalysisEventsController(streams)).setControllerAdvice(new GlobalExceptionHandler()).build();
            var request=mvc.perform(get("/api/copilot/review-analyses/{id}/events",id).header("Authorization","Bearer token"))
                .andExpect(request().asyncStarted()).andReturn();
            request.getAsyncResult(3000);
            String body=mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("event:stream-error","UNAUTHENTICATED").doesNotContain("secret-token","raw-prompt","analysis-state");
        }
    }
    @Test void batchesReplayInOrderAndReauthorizeBeforeEachBatch() throws Exception {
        var id=UUID.randomUUID();var access=mock(GetAnalysisService.class);var events=mock(AnalysisEventRepository.class);
        when(access.authorize(id,"token")).thenReturn(new GetAnalysisService.AuthorizedAnalysis("reviewer",new AnalysisView(id,AnalysisStatus.SUCCEEDED,null,"{}")));
        when(events.head(id)).thenReturn(130L);
        when(events.after(eq(id),anyLong(),eq(64))).thenAnswer(invocation->{
            long cursor=invocation.getArgument(1);var batch=new ArrayList<AnalysisEventRepository.Event>();
            for(long i=cursor+1;i<=Math.min(cursor+64,130);i++) batch.add(new AnalysisEventRepository.Event(id,Long.toString(i),"STATE_CHANGED",i==130?AnalysisStatus.SUCCEEDED:AnalysisStatus.GENERATING,null,i==130,Instant.now()));
            return batch;
        });
        try(var streams=new AnalysisEventStream(access,events,new AnalysisWakeups(),Duration.ofSeconds(2),Duration.ofMillis(10))) {
            var mvc=MockMvcBuilders.standaloneSetup(new AnalysisEventsController(streams)).build();
            var request=mvc.perform(get("/api/copilot/review-analyses/{id}/events",id).header("Authorization","Bearer token").header("Last-Event-ID","0")).andReturn();
            request.getAsyncResult(3000);
            String body=mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body.lines().filter(line->line.startsWith("id:")).map(line->line.substring(3)).toList())
                .containsExactly(java.util.stream.LongStream.rangeClosed(1,130).mapToObj(Long::toString).toArray(String[]::new));
            verify(access,times(4)).authorize(id,"token");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(streams.active()).isZero());
        }
    }
}
