package com.finguard.copilot.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/** Stable content addressing; never log the input value. */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER=new ObjectMapper().findAndRegisterModules();
    private CanonicalJson() {}
    public static JsonNode tree(Object value) { return sorted(MAPPER.valueToTree(value)); }
    public static String encode(Object value) { return tree(value).toString(); }
    public static String hash(Object value) { return sha256(encode(value)); }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static JsonNode sorted(JsonNode node) {
        if(node.isObject()) {
            ObjectNode result=MAPPER.createObjectNode();
            var fields=new TreeMap<String,JsonNode>();
            node.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));
            fields.forEach((key,value)->result.set(key,sorted(value)));
            return result;
        }
        if(node.isArray()) {
            ArrayNode result=MAPPER.createArrayNode();
            node.forEach(item->result.add(sorted(item))); return result;
        }
        if(node.isFloatingPointNumber()) return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        return node.deepCopy();
    }
}
