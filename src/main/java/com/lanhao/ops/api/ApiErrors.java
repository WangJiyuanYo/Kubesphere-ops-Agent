package com.lanhao.ops.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class, com.lanhao.ops.port.DeploymentPort.Superseded.class,com.lanhao.ops.console.ContextMismatch.class})
    public ResponseEntity<ProblemDetail> conflict(Exception error) {
        return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "请求与当前任务状态冲突，请检查幂等键、当前方案和进行中的任务"));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception error) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数无效"));
    }
}
