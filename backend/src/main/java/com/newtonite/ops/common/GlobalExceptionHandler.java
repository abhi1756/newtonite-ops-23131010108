package com.newtonite.ops.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps every failure to one consistent problem+json shape: {status, title, detail, code, ...}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getProperties());
    }

    /** JPA @Version detected a concurrent write between our read and our write. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "STALE_VERSION",
                "This item was changed by someone else. Reload it and try again.", Map.of());
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ProblemDetail> handleValidation(BindException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getFieldErrors().forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ex.getGlobalErrors().forEach(e -> errors.putIfAbsent(e.getObjectName(), e.getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request is invalid", Map.of("errors", errors));
    }

    /** Constraint annotations directly on controller parameters, e.g. {@code @Max(100) int limit}. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(r -> errors.putIfAbsent(
                r.getMethodParameter().getParameterName(), r.getResolvableErrors().get(0).getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request is invalid", Map.of("errors", errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ProblemDetail> handleMalformed(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request could not be read: " + rootMessage(ex), Map.of());
    }

    /** A database constraint caught something the service layer did not (should be rare). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Constraint violation", ex);
        return problem(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION", "The request conflicts with existing data", Map.of());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                         Map<String, Object> properties) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        properties.forEach(pd::setProperty);
        return ResponseEntity.status(status).body(pd);
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg.split("\n")[0];
    }
}
