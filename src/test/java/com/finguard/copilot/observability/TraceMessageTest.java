package com.finguard.copilot.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.dispatch.AnalysisRequestedMessage;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.OutboxRecord;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TraceMessageTest {
    @Test void oldMessagesWorkAndNewContextRoundTripsWithoutAllowingHeaderInjection() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var id=UUID.randomUUID();
        var old=new AnalysisRequestedMessage(1,UUID.randomUUID(),id,Instant.now());
        assertThat(old.traceparent()).isNull();
        String parent="00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01";
        var next=old.withTraceparent(parent);
        assertThat(json.readValue(json.writeValueAsBytes(next),AnalysisRequestedMessage.class).traceparent()).isEqualTo(parent);
        assertThat(old.withTraceparent("\r\nAuthorization: SECRET").traceparent()).isNull();
        var malformed=json.valueToTree(next);
        ((com.fasterxml.jackson.databind.node.ObjectNode)malformed).set("traceparent",json.createObjectNode().put("secret","SECRET"));
        assertThat(json.treeToValue(malformed,AnalysisRequestedMessage.class).traceparent()).isNull();
        assertThat(new OutboxRecord(old.eventId(),id,1,old.occurredAt(),old.occurredAt(),parent).traceparent()).isEqualTo(parent);
    }
}
