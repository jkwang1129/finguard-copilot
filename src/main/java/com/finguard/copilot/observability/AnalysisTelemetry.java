package com.finguard.copilot.observability;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import com.finguard.copilot.common.error.CopilotException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

/** Explicit, bounded-vocabulary telemetry. Never accepts business text or exceptions as attributes. */
public final class AnalysisTelemetry {
    public enum Stage { API_CREATE, CORE_CONTEXT, SNAPSHOT, OUTBOX_PUBLISH, WORKER, RETRIEVAL,
        CONTEXT, PREFLIGHT, MODEL, VALIDATION, ACCOUNTING, PERSISTENCE, AGENT_GRAPH, AGENT_TOOL, CHECKPOINT, HUMAN_RESUME }
    private static final ContextKey<java.util.concurrent.atomic.AtomicReference<UUID>> ANALYSIS=ContextKey.named("finguard.analysis");
    private static final AnalysisTelemetry NOOP=new AnalysisTelemetry(OpenTelemetry.noop().getTracer("noop"),null);
    private final Tracer tracer;
    private final MeterRegistry meters;
    public AnalysisTelemetry(Tracer tracer,MeterRegistry meters) {
        this.tracer=tracer;this.meters=meters;
        if(meters!=null) for(String provider:java.util.List.of("deepseek","qwen","zhipu","stub","OTHER"))
            safely(()->meters.counter("copilot.provider.request.attempts","provider",provider));
    }
    private static void safely(Runnable action){try {action.run();} catch(RuntimeException ignored){/* telemetry is best effort */}}
    private static String mdc(String key){try{return MDC.get(key);}catch(RuntimeException ignored){return null;}}
    public static AnalysisTelemetry noop() {return NOOP;}
    public static String validTraceparent(String value) {
        if(value==null || value.length()!=55 || !value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")) return null;
        if(value.substring(3,35).equals("0".repeat(32)) || value.substring(36,52).equals("0".repeat(16))) return null;
        return value;
    }
    public String traceparent() {
        try {var c=Span.current().getSpanContext();
            return c.isValid()?"00-"+c.getTraceId()+"-"+c.getSpanId()+"-"+(c.isSampled()?"01":"00"):null;
        } catch(RuntimeException ignored){return null;}
    }
    public TraceScope start(Stage stage,UUID id,String parent) {
        try {return startObserved(stage,id,parent);}
        catch(RuntimeException ignored){return NOOP.new TraceScope(stage,Span.getInvalid(),Scope.noop(),null);}
    }
    private TraceScope startObserved(Stage stage,UUID id,String parent) {
        Context context=Context.current();
        String safe=validTraceparent(parent);
        if(safe!=null) {
            var remote=SpanContext.createFromRemoteParent(safe.substring(3,35),safe.substring(36,52),
                    safe.endsWith("01")?TraceFlags.getSampled():TraceFlags.getDefault(),TraceState.getDefault());
            context=Context.root().with(Span.wrap(remote));
        } else if(stage==Stage.API_CREATE || stage==Stage.WORKER || stage==Stage.OUTBOX_PUBLISH) context=Context.root();
        var analysis=context.get(ANALYSIS);
        if(analysis==null || id!=null) analysis=new java.util.concurrent.atomic.AtomicReference<>(id);
        if(id==null) id=analysis.get();
        var span=tracer.spanBuilder("copilot."+stage.name().toLowerCase(java.util.Locale.ROOT))
                .setParent(context).setAttribute("finguard.stage",stage.name()).startSpan();
        if(id!=null) span.setAttribute("finguard.analysis_id",id.toString());
        span.setAttribute("langfuse.observation.type",stage==Stage.MODEL?"generation":"span");
        return new TraceScope(stage,span,context.with(span).with(ANALYSIS,analysis).makeCurrent(),id);
    }
    public <T> T call(Stage stage,Supplier<T> action) {
        try(var scope=start(stage,null,null)) {
            try {return action.get();}
            catch(RuntimeException e) {scope.failure(e instanceof CopilotException c?c.code().name():"INTERNAL_ERROR");throw e;}
        }
    }
    public void run(Stage stage,Runnable action) {call(stage,()->{action.run();return null;});}
    public void analysisId(UUID id) {
        if(id!=null) safely(()->{Span.current().setAttribute("finguard.analysis_id",id.toString());
            var ref=Context.current().get(ANALYSIS);if(ref!=null) ref.set(id);MDC.put("analysisId",id.toString());});
    }
    public void manifest(String sha256) {if(sha256!=null && sha256.matches("[0-9a-f]{64}")) safely(()->Span.current().setAttribute("finguard.manifest_sha256",sha256));}
    public void modelMetadata(String provider,String model,String promptVersion,String configurationVersion) {
        safely(()->{
        var span=Span.current();
        span.setAttribute("gen_ai.system",TelemetryVocabulary.provider(provider));
        span.setAttribute("gen_ai.request.model",TelemetryVocabulary.model(model));
        span.setAttribute("finguard.prompt_version_sha256",com.finguard.copilot.governance.CanonicalJson.sha256(promptVersion));
        span.setAttribute("finguard.model_version_sha256",com.finguard.copilot.governance.CanonicalJson.sha256(configurationVersion));
        });
    }
    public void providerAttempt(String provider) {
        if(meters!=null) safely(()->meters.counter("copilot.provider.request.attempts","provider",TelemetryVocabulary.provider(provider)).increment());
    }
    public void toolName(String name){
        String fixed=name!=null && java.util.Set.of("get_review_facts","get_task_state","search_review_policy").contains(name)?name:"OTHER";
        safely(()->Span.current().setAttribute("finguard.tool_name",fixed));
    }
    public final class TraceScope implements AutoCloseable {
        private final Stage stage; private final Span span; private final Scope scope;
        private final long started=System.nanoTime(); private final String oldTrace=mdc("traceId"),oldSpan=mdc("spanId"),oldAnalysis=mdc("analysisId");
        private boolean closed; private String outcome="SUCCESS";
        private TraceScope(Stage stage,Span span,Scope scope,UUID id) {
            this.stage=stage;this.span=span;this.scope=scope;
            safely(()->{if(span.getSpanContext().isValid()) {MDC.put("traceId",span.getSpanContext().getTraceId());MDC.put("spanId",span.getSpanContext().getSpanId());}});
            if(id!=null) safely(()->MDC.put("analysisId",id.toString()));
        }
        public void failure(String code) {
            outcome="FAILURE";
            safely(()->{span.setStatus(StatusCode.ERROR);span.setAttribute("finguard.error_code",SafeSpanData.errorCode(code));});
        }
        public void outcome(String value) {
            outcome=java.util.Set.of("SUCCESS","FAILURE","NOOP","RETRY","REJECTED").contains(value)?value:"FAILURE";
            if(outcome.equals("FAILURE") || outcome.equals("REJECTED")) safely(()->span.setStatus(StatusCode.ERROR));
        }
        @Override public void close() {
            if(closed) return;closed=true;
            try {safely(()->span.setAttribute("finguard.outcome",outcome));safely(scope::close);safely(span::end);}
            finally {restore("traceId",oldTrace);restore("spanId",oldSpan);restore("analysisId",oldAnalysis);}
            if(meters!=null) safely(()->Timer.builder("copilot.stage.duration").tags("stage",stage.name(),"outcome",outcome)
                    .register(meters).record(System.nanoTime()-started,TimeUnit.NANOSECONDS));
        }
        private void restore(String key,String value) {safely(()->{if(value==null) MDC.remove(key);else MDC.put(key,value);});}
    }
}
