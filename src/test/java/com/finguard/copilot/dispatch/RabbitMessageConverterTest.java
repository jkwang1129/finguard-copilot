package com.finguard.copilot.dispatch;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class RabbitMessageConverterTest {
    @Test void roundTripsTheSingleDocumentedWireDtoWithoutTrustingItsWholePackage(){
        var converter=new RabbitTopologyConfiguration().rabbitMessageConverter();
        var original=new AnalysisRequestedMessage(1,UUID.randomUUID(),UUID.randomUUID(),Instant.now());
        Message encoded=converter.toMessage(original,new MessageProperties());
        assertThat(encoded.getMessageProperties().getHeader("__TypeId__").toString()).isEqualTo(AnalysisRequestedMessage.class.getName());
        assertThat(converter.fromMessage(encoded)).isEqualTo(original);
    }
    @Test void arbitraryBrokerTypeHeadersRemainRejected(){
        var converter=new RabbitTopologyConfiguration().rabbitMessageConverter();
        for(String type:new String[]{"com.finguard.copilot.config.SecretValue","java.io.File"}){
            var properties=new MessageProperties();properties.setContentType("application/json");properties.setHeader("__TypeId__",type);
            assertThatThrownBy(()->converter.fromMessage(new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),properties))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trusted");
        }
    }
}
