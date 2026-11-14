package com.lanhao.ops.console;

/** 仅使用本地固定文本，不含上游异常或凭据。 */
public class ContextMismatch extends ConsoleFailure {
    public ContextMismatch(String message) { super(message); }
}
