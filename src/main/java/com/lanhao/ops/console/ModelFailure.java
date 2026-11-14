package com.lanhao.ops.console;

/** 只携带本地错误类别和 HTTP 状态；不保留上游正文、地址、请求或异常链。 */
public final class ModelFailure extends ConsoleFailure {
    public enum Code {
        CONFIGURATION("模型凭据配置不可用，请检查 API Key 以及配套的服务端主密钥"),
        AUTHENTICATION("模型认证失败，请检查已保存的 API Key"),
        PERMISSION("模型服务拒绝访问，请检查账号或分组的模型权限"),
        MODEL_UNAVAILABLE("当前账号或分组不支持此模型，请重新获取模型并测试可调用性"),
        RATE_LIMIT("模型服务限流或额度不足，请检查配额，稍后手动重试"),
        UPSTREAM_UNAVAILABLE("模型上游服务暂不可用，请稍后手动重试"),
        INVALID_REQUEST("模型服务不接受当前请求，请检查模型、协议与采样参数"),
        ENDPOINT_NOT_FOUND("模型接口不存在，请检查 Base URL 与协议路径"),
        REDIRECT("模型接口返回重定向，已停止请求，请填写最终 API 地址"),
        TIMEOUT("等待模型回复超时，请稍后手动重试；本次未自动重发请求"),
        CONNECTION("无法连接模型服务，请检查网络、地址和 TLS 证书"),
        EMPTY_RESPONSE("模型未返回可用文本，请检查模型协议及输出 Token 预算"),
        INVALID_RESPONSE("模型响应格式无效，请检查网关协议兼容性"),
        UNKNOWN("模型请求未完成，请检查服务状态或联系管理员查看错误分类");
        final String message;
        Code(String message){this.message=message;}
    }
    private final Code code;
    private final int status;
    public ModelFailure(Code code,int status) {
        super("[LLM_"+code.name()+"] "+code.message+(status>0?"（HTTP "+status+"）":""));
        this.code=code;this.status=status;
    }
    public Code code(){return code;}
    public int status(){return status;}
}
