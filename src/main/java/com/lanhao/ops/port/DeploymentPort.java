package com.lanhao.ops.port;

import com.lanhao.ops.domain.Model.*;

/** 采集与恢复使用独立权限；恢复实现必须保留 requestKey 幂等语义。 */
public interface DeploymentPort {
    default String clusterApiUrl() { return ""; }
    default void validateRegistration(StartRequest request) { }
    default StartRequest registration(String provisioningId) { throw new IllegalStateException("当前执行模式不支持查询平台发布身份"); }
    default java.util.List<Target> discover(Run run) { return java.util.List.of(); }
    Evidence observe(Run run);
    default java.util.Optional<Recovery> lookupRecovery(Run run) { return java.util.Optional.empty(); }
    Recovery retry(Run run);
    boolean verify(Run run);

    class Superseded extends RuntimeException {
        public Superseded() { super("目标已删除、替换或发布了新版本，本次任务停止"); }
        public Superseded(String message) { super(message); }
    }
}
