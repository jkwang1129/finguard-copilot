package com.finguard.copilot.observability;

import java.io.IOException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/** Untrusted telemetry metadata must not make an otherwise valid business message invalid. */
public final class TraceparentDeserializer extends StdDeserializer<String> {
    public TraceparentDeserializer(){super(String.class);}
    @Override public String deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.currentToken()==JsonToken.VALUE_STRING) return AnalysisTelemetry.validTraceparent(parser.getText());
        parser.skipChildren();return null;
    }
}
