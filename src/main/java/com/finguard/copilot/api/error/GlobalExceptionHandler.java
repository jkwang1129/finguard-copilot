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
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return problem(exception.code(), status, exception.getMessage(), request);
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
