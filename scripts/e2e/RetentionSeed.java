import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

class RetentionSeed {
 public static void main(String[] args) {
  String auth=Base64.getEncoder().encodeToString((System.getenv("COPILOT_LANGFUSE_PUBLIC_KEY")+":"+System.getenv("COPILOT_LANGFUSE_SECRET_KEY")).getBytes(StandardCharsets.UTF_8));
  var exporter=OtlpHttpSpanExporter.builder().setEndpoint("http://127.0.0.1:13001/api/public/otel/v1/traces")
    .addHeader("Authorization","Basic "+auth).addHeader("x-langfuse-ingestion-version","4").setRetryPolicy(null).setTimeout(Duration.ofSeconds(2)).build();
  var provider=SdkTracerProvider.builder().addSpanProcessor(BatchSpanProcessor.builder(exporter).setScheduleDelay(Duration.ofMillis(100)).build()).build();
  for(boolean old:new boolean[]{true,false}) {
    Instant at=old?Instant.now().minus(Duration.ofDays(8)):Instant.now();
    var span=provider.get("finguard-retention-acceptance").spanBuilder(old?"retention-expired-fixture":"retention-recent-fixture")
      .setStartTimestamp(at).startSpan();
    span.setAttribute("langfuse.observation.type","span");
    span.end(at.plusMillis(1).getEpochSecond()*1_000_000_000L+at.plusMillis(1).getNano(),TimeUnit.NANOSECONDS);
    System.out.println((old?"expired":"recent")+"="+span.getSpanContext().getTraceId());
  }
  if(!provider.forceFlush().join(5,TimeUnit.SECONDS).isSuccess()) throw new IllegalStateException("fixture export unconfirmed");
  provider.shutdown().join(2,TimeUnit.SECONDS);
 }
}
