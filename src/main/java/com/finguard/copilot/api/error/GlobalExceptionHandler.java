package com.finguard.copilot.api.error;

import java.net.URI;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.CoreClientException;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.AnalysisCreationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INTERNAL_DETAIL = "The server could not process the request";

    @ExceptionHandler(CopilotException.class)
    ResponseEntity<ProblemDetail> handleCopilotException(
            CopilotException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = switch (exception.code()) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case OUTPUT_SCHEMA_INVALID, FACT_VALIDATION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case MODEL_OUTPUT_INVALID, MODEL_CONFIGURATION_INVALID -> HttpStatus.INTERNAL_SERVER_ERROR;
            case MODEL_BUDGET_EXCEEDED, MODEL_CAPACITY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case KNOWLEDGE_UNAVAILABLE, MODEL_UNAVAILABLE, MODEL_TIMEOUT, MODEL_RATE_LIMITED -> HttpStatus.SERVICE_UNAVAILABLE;
            case BROKER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case ANALYSIS_RETRY_EXHAUSTED, ANALYSIS_WORKER_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONTEXT_BUDGET_EXCEEDED, VALIDATION_FAILED, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return problem(exception.code(), status, exception.getMessage(), request);
    }

    @ExceptionHandler(CoreClientException.class)
    ResponseEntity<ProblemDetail> handleCoreClientException(
            CoreClientException exception,
            HttpServletRequest request
    ) {
        ErrorCode code = switch (exception.kind()) {
            case UNAUTHENTICATED -> ErrorCode.UNAUTHENTICATED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case UNAVAILABLE, INVALID_RESPONSE -> ErrorCode.SERVICE_UNAVAILABLE;
        };
        HttpStatus status = switch (code) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String detail = switch (code) {
            case UNAUTHENTICATED -> "Core authentication is required";
            case FORBIDDEN -> "Core access is forbidden";
            case RESOURCE_NOT_FOUND -> "The requested Core resource was not found";
            case SERVICE_UNAVAILABLE -> "Core service is unavailable";
            default -> INTERNAL_DETAIL;
        };
        return problem(code, status, detail, request);
    }

    @ExceptionHandler(AnalysisCreationException.class)
    ResponseEntity<ProblemDetail> handleAnalysisCreationException(
            AnalysisCreationException exception,
            HttpServletRequest request
    ) {
        ErrorCode code = exception.kind() == AnalysisCreationException.Kind.CONFLICT
                ? ErrorCode.CONFLICT
                : ErrorCode.INTERNAL_ERROR;
        HttpStatus status = code == ErrorCode.CONFLICT
                ? HttpStatus.CONFLICT
                : HttpStatus.INTERNAL_SERVER_ERROR;
        String detail = code == ErrorCode.CONFLICT
                ? "The idempotency key is already active for a different request"
                : INTERNAL_DETAIL;
        return problem(code, status, detail, request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class
    })
    ResponseEntity<ProblemDetail> handleValidationException(
            Exception exception,
            HttpServletRequest request
    ) {
        return problem(
                ErrorCode.VALIDATION_FAILED,
                HttpStatus.BAD_REQUEST,
                "One or more request values are invalid",
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return problem(
                ErrorCode.INVALID_REQUEST,
                HttpStatus.BAD_REQUEST,
                "The request body is malformed or unreadable",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        LOGGER.error(
                "Unexpected request failure at {} ({})",
                request.getRequestURI(),
                exception.getClass().getName()
        );
        return problem(
                ErrorCode.INTERNAL_ERROR,
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_DETAIL,
                request
        );
    }

    private ResponseEntity<ProblemDetail> problem(
            ErrorCode code,
            HttpStatus status,
            String detail,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:finguard:error:" + kebabCase(code.name())));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        return ResponseEntity.status(status).body(problem);
    }

    private String kebabCase(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
