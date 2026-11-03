package com.finguard.copilot.observability;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.*;
import com.finguard.copilot.common.error.ErrorCode;

/** Final export boundary: every free-text surface is removed or rebuilt from fixed vocabulary. */
public final class SafeSpanData implements SpanData {
    private final Attributes attributes; private final String name;
    private final SpanContext context,parent;
    private final SpanKind kind;
    private final long started,ended;
    private final boolean hasEnded;
    private final StatusData status;
    private SafeSpanData(SpanData source) {
        context=clean(source.getSpanContext());parent=clean(source.getParentSpanContext());kind=source.getKind();
        started=source.getStartEpochNanos();ended=source.getEndEpochNanos();hasEnded=source.hasEnded();
        status=StatusData.create(source.getStatus().getStatusCode(),"");
        var b=Attributes.builder();
        String raw=source.getAttributes().get(AttributeKey.stringKey("finguard.stage"));
        AnalysisTelemetry.Stage stage;
        try {stage=AnalysisTelemetry.Stage.valueOf(raw);} catch(RuntimeException e) {stage=null;}
        name=stage==null?"copilot.unknown":"copilot."+stage.name().toLowerCase(java.util.Locale.ROOT);
        if(stage!=null) b.put("finguard.stage",stage.name());
        String id=source.getAttributes().get(AttributeKey.stringKey("finguard.analysis_id"));
        if(id!=null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) b.put("finguard.analysis_id",id);
        String hash=source.getAttributes().get(AttributeKey.stringKey("finguard.manifest_sha256"));
        if(hash!=null && hash.matches("[0-9a-f]{64}")) b.put("finguard.manifest_sha256",hash);
        for(String key:List.of("finguard.prompt_version_sha256","finguard.model_version_sha256")) {
            String value=source.getAttributes().get(AttributeKey.stringKey(key));
            if(value!=null && value.matches("[0-9a-f]{64}")) b.put(key,value);
        }
        String provider=source.getAttributes().get(AttributeKey.stringKey("gen_ai.system"));
        if(provider!=null) b.put("gen_ai.system",TelemetryVocabulary.provider(provider));
        String model=source.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"));
        if(model!=null) b.put("gen_ai.request.model",TelemetryVocabulary.model(model));
        String error=source.getAttributes().get(AttributeKey.stringKey("finguard.error_code"));
        if(error!=null) b.put("finguard.error_code",errorCode(error));
        String outcome=source.getAttributes().get(AttributeKey.stringKey("finguard.outcome"));
        if(outcome!=null && Set.of("SUCCESS","FAILURE","NOOP","RETRY","REJECTED").contains(outcome)) b.put("finguard.outcome",outcome);
        b.put("langfuse.observation.type",stage==AnalysisTelemetry.Stage.MODEL?"generation":"span");
        attributes=b.build();
    }
    public static SpanData copy(SpanData source) {return new SafeSpanData(source);}
    public static String errorCode(String code) {
        if(code==null) return "INTERNAL_ERROR";
        try {return ErrorCode.valueOf(code).name();} catch(IllegalArgumentException ignored) {
            return Set.of("MODEL_CALL_OUTCOME_UNKNOWN","MODEL_PREPARATION_UNAVAILABLE","ANALYSIS_RETRY_EXHAUSTED").contains(code)?code:"INTERNAL_ERROR";
        }
    }
    @Override public Attributes getAttributes(){return attributes;}
    @Override public int getTotalAttributeCount(){return attributes.size();}
    @Override public String getName(){return name;}
    @Override public List<EventData> getEvents(){return List.of();}
    @Override public List<LinkData> getLinks(){return List.of();}
    @Override public int getTotalRecordedEvents(){return 0;}
    @Override public int getTotalRecordedLinks(){return 0;}
    @Override public StatusData getStatus(){return status;}
    @Override public SpanKind getKind(){return kind;}
    @Override public long getStartEpochNanos(){return started;}
    @Override public long getEndEpochNanos(){return ended;}
    @Override public boolean hasEnded(){return hasEnded;}
    @Override public Resource getResource(){return Resource.create(Attributes.of(AttributeKey.stringKey("service.name"),"finguard-copilot"));}
    @Override public InstrumentationScopeInfo getInstrumentationScopeInfo(){return InstrumentationScopeInfo.create("finguard-copilot");}
    @SuppressWarnings("deprecation")
    @Override public io.opentelemetry.sdk.common.InstrumentationLibraryInfo getInstrumentationLibraryInfo(){return io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create("finguard-copilot",null);}
    @Override public SpanContext getSpanContext(){return context;}
    @Override public SpanContext getParentSpanContext(){return parent;}
    private static SpanContext clean(SpanContext c){return c.isValid()?SpanContext.create(c.getTraceId(),c.getSpanId(),c.getTraceFlags(),TraceState.getDefault()):SpanContext.getInvalid();}
}
