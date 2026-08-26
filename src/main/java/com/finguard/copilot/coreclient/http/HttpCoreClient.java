package com.finguard.copilot.coreclient.http;

import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CoreClientException;
import com.finguard.copilot.coreclient.port.CurrentUser;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class HttpCoreClient implements CoreClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpCoreClient.class);
    private static final String CURRENT_USER_PATH = "/api/auth/me";
    private static final String REVIEW_TASK_CONTEXT_PATH = "/api/review-tasks/{reviewTaskId}/context";
    private static final Pattern HEADER_SAFE_JWT = Pattern.compile(
            "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"
    );

    private final RestClient restClient;
    private final ObjectMapper responseObjectMapper;

    public HttpCoreClient(RestClient restClient, ObjectMapper responseObjectMapper) {
        this.restClient = restClient;
        this.responseObjectMapper = responseObjectMapper;
    }

    @Override
    public CurrentUser currentUser(String bearerToken) {
        CoreCurrentUserResponse response = get(
                "current user",
                CURRENT_USER_PATH,
                bearerToken,
                CoreCurrentUserResponse.class
        );
        try {
            return response.toPort();
        } catch (RuntimeException exception) {
            throw invalidResponse("current user");
        }
    }

    @Override
    public ReviewTaskContext reviewTaskContext(long reviewTaskId, String bearerToken) {
        CoreReviewTaskContextResponse response = get(
                "review task context",
                REVIEW_TASK_CONTEXT_PATH,
                bearerToken,
                CoreReviewTaskContextResponse.class,
                reviewTaskId
        );
        try {
            return response.toPort();
        } catch (RuntimeException exception) {
            throw invalidResponse("review task context");
        }
    }

    private <T> T get(String endpoint, String path, String bearerToken, Class<T> responseType, Object... uriVariables) {
        requireBearerToken(bearerToken);
        try {
            String responseBody = restClient.get()
                    .uri(path, uriVariables)
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve()
                    .onStatus(statusCode -> !statusCode.is2xxSuccessful(), (request, response) -> {
                        throw upstreamStatus(endpoint, response.getStatusCode());
                    })
                    .body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                throw invalidResponse(endpoint);
            }
            return responseObjectMapper.readValue(responseBody, responseType);
        } catch (CoreClientException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            LOGGER.warn("Core {} request is unavailable", endpoint);
            throw unavailable(endpoint);
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Core {} response is invalid", endpoint);
            throw invalidResponse(endpoint);
        } catch (RestClientException exception) {
            LOGGER.warn("Core {} request is unavailable", endpoint);
            throw unavailable(endpoint);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Core {} request could not be constructed", endpoint);
            throw exception(CoreClientException.Kind.UNAUTHENTICATED, "Bearer token is required");
        }
    }

    private CoreClientException upstreamStatus(String endpoint, HttpStatusCode statusCode) {
        int status = statusCode.value();
        CoreClientException.Kind kind = switch (status) {
            case 401 -> CoreClientException.Kind.UNAUTHENTICATED;
            case 403 -> CoreClientException.Kind.FORBIDDEN;
            case 404 -> CoreClientException.Kind.NOT_FOUND;
            default -> CoreClientException.Kind.UNAVAILABLE;
        };
        LOGGER.warn("Core {} request failed with status {}", endpoint, status);
        return exception(kind, "Core " + endpoint + " request failed");
    }

    private void requireBearerToken(String bearerToken) {
        if (bearerToken == null || !HEADER_SAFE_JWT.matcher(bearerToken).matches()) {
            throw exception(CoreClientException.Kind.UNAUTHENTICATED, "Bearer token is required");
        }
    }

    private CoreClientException unavailable(String endpoint) {
        return exception(CoreClientException.Kind.UNAVAILABLE, "Core " + endpoint + " is unavailable");
    }

    private CoreClientException invalidResponse(String endpoint) {
        return exception(CoreClientException.Kind.INVALID_RESPONSE, "Core " + endpoint + " response is invalid");
    }

    private CoreClientException exception(CoreClientException.Kind kind, String message) {
        return new CoreClientException(kind, message, null);
    }
}
