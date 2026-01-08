package com.example.gb.ai.tools;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,String>> badReq(MethodArgumentNotValidException ex) {
        var m = new LinkedHashMap<String,String>();
        ex.getBindingResult().getFieldErrors().forEach(e -> m.put(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(m);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String,String>> badReq2(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,String>> oops(Exception ex) {
        return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
    }
}