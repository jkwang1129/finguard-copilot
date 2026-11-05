package com.finguard.copilot.agent.mcp;
import java.util.*;
/** Constructible only by the capability service; never deserialize this from a request. */
public final class AuthorizedMcpSession {
    private final UUID analysisId;private final String reviewer,capabilityHash;private final long revision;private final Set<String> tools;
    AuthorizedMcpSession(UUID id,String reviewer,String hash,long revision,Set<String> tools){
        this.analysisId=id;this.reviewer=reviewer;this.capabilityHash=hash;this.revision=revision;this.tools=Set.copyOf(tools);
    }
    public UUID analysisId(){return analysisId;}public String reviewer(){return reviewer;}
    public String capabilityHash(){return capabilityHash;}public long revision(){return revision;}public Set<String> tools(){return tools;}
    @Override public String toString(){return "AuthorizedMcpSession[scoped]";}
}
