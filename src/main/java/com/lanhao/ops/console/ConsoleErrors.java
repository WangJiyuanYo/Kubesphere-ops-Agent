package com.lanhao.ops.console;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.Order;

@Order(0)
@RestControllerAdvice(basePackages="com.lanhao.ops.console")
public class ConsoleErrors {
    @ExceptionHandler(ContextMismatch.class)
    public ResponseEntity<ProblemDetail> contextChanged(ContextMismatch failure) {
        return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,failure.getMessage()));
    }
    @ExceptionHandler(ConsoleFailure.class)
    public ResponseEntity<ProblemDetail> unavailable(ConsoleFailure failure) {
        return ResponseEntity.status(502).body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,failure.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"请求参数无效，请检查字段格式、目标范围与版本；更换服务地址时需重新填写凭据"));
    }
}
