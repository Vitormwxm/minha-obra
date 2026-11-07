package br.com.minhaobra.controller;

import br.com.minhaobra.service.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> application(ApiException error) {
        var builder = ResponseEntity.status(error.status()).header("Cache-Control", "no-store");
        if (error.status() == 429)
            builder.header("Retry-After", "60");
        return builder.body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException error) {
        Map<String, String> fields = new LinkedHashMap<>();
        error.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), "Confira este campo."));
        return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(
                Map.of("code", "VALIDATION_FAILED", "message", "Confira os campos informados.", "fieldErrors", fields));
    }

    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class })
    ResponseEntity<?> malformed(Exception ignored) {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("code", "INVALID_REQUEST",
                "message", "Solicitação inválida. Confira os campos e tente novamente."));
    }
}
