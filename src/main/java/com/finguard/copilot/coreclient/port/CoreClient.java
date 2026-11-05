package com.finguard.copilot.coreclient.port;

public interface CoreClient {

    CurrentUser currentUser(String bearerToken);

    ReviewTaskContext reviewTaskContext(long reviewTaskId, String bearerToken);

    default ReviewTaskState reviewTaskState(long reviewTaskId, String bearerToken) {
        throw new CoreClientException(CoreClientException.Kind.UNAVAILABLE,
                "Core task state capability is unavailable", null);
    }
}
