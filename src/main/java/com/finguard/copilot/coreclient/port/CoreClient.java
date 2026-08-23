package com.finguard.copilot.coreclient.port;

public interface CoreClient {

    CurrentUser currentUser(String bearerToken);

    ReviewTaskContext reviewTaskContext(long reviewTaskId, String bearerToken);
}
