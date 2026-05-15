package com.certguard.auth.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AuthException.class)
    ProblemDetail handleAuth(AuthException ex, WebRequest req) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> handleRateLimit(TooManyRequestsException ex) {
        ProblemDetail pd = problem(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "300")
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", detail);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleAll(Exception ex, WebRequest req) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://certguard.cloud/problems/" +
                title.toLowerCase().replace(' ', '-')));
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
