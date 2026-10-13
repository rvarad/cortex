package com.cortex.cortex_ingestion.exception;

import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * The boundary for every REST request into ingestion (ADR-0014). Code below it lets failures
 * propagate; this is the one place they are logged and turned into an answer. Every error body is
 * an RFC 9457 ProblemDetail.
 *
 * <p>The base class already answers Spring's own exceptions (ResponseStatusException, validation,
 * a malformed path variable, an unknown path) with the right status. Everything else is a 500 whose
 * body says nothing about the cause.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");
    return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  /** The detail says which field is wrong. The messages are our own DTO annotations', so safe to show. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ex.getBody().setDetail(ex.getBindingResult().getAllErrors().stream()
        .map(DefaultMessageSourceResolvable::getDefaultMessage)
        .collect(Collectors.joining("; ")));
    return handleExceptionInternal(ex, null, headers, status, request);
  }

  /** Every answer from this class passes through here, so this is where each failure is logged, once. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
      HttpStatusCode statusCode, WebRequest request) {
    logOnce(ex, statusCode, request);
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }

  private void logOnce(Exception ex, HttpStatusCode statusCode, WebRequest request) {
    String fileId = fileIdOf(request);
    String previousFileId = MDC.get("fileId");
    if (fileId != null) {
      MDC.put("fileId", fileId);
    }
    try {
      String call = describe(request);
      if (statusCode.is5xxServerError()) {
        log.error("{} failed with {}", call, statusCode.value(), ex);
      } else {
        // A 4xx is the caller's mistake, not ours: no stack trace. The detail, not the message:
        // a validation message is a screenful and repeats the caller's input.
        String reason = ex instanceof ErrorResponse errorResponse ? errorResponse.getBody().getDetail() : ex.getMessage();
        log.warn("{} answered {}: {}", call, statusCode.value(), reason);
      }
    } finally {
      if (previousFileId != null) {
        MDC.put("fileId", previousFileId);
      } else {
        MDC.remove("fileId");
      }
    }
  }

  /** The {fileId} path variable, when the matched endpoint has one. */
  private static String fileIdOf(WebRequest request) {
    Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
        RequestAttributes.SCOPE_REQUEST);
    return variables instanceof Map<?, ?> map && map.get("fileId") instanceof String fileId ? fileId : null;
  }

  private static String describe(WebRequest request) {
    return request instanceof ServletWebRequest servletRequest
        ? servletRequest.getRequest().getMethod() + " " + servletRequest.getRequest().getRequestURI()
        : request.getDescription(false);
  }
}
