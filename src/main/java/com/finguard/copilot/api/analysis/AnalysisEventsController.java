package com.finguard.copilot.api.analysis;

import java.util.UUID;
import com.finguard.copilot.events.AnalysisEventStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/copilot/review-analyses/{analysisId}/events")
public class AnalysisEventsController {
    private final AnalysisEventStream streams;
    public AnalysisEventsController(AnalysisEventStream streams){this.streams=streams;}
    @GetMapping(produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID analysisId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @RequestHeader(value="Last-Event-ID",required=false) String cursor,HttpServletResponse response) {
        var emitter=streams.open(analysisId,ReviewAnalysisController.bearerToken(authorization),cursor);
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
        return emitter;
    }
}
