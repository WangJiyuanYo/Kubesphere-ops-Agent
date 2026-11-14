package com.lanhao.ops.console;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChatInspectionSafetyTest {
    @Test void acceptsOnlyFixedReadPathsAndBoundedParametersInFrozenNamespace() {
        for(String path:new String[]{"/api/v1/namespaces/demo/pods?limit=100",
                "/api/v1/namespaces/demo/pods/web/log?container=app&tailLines=60&limitBytes=4096&previous=true",
                "/api/v1/namespaces/demo/events?fieldSelector=involvedObject.uid%3Dpod-uid&limit=100"})
            assertThatCode(()->ChatInspectionGraph.reviewRequest(path,"demo")).doesNotThrowAnyException();
        for(String path:new String[]{"https://evil.example/api/v1/namespaces/demo/pods", "//evil.example/pods",
                "/api/v1/namespaces/other/pods", "/api/v1/namespaces/demo/secrets", "/api/v1/namespaces/demo/pods/web/exec",
                "/api/v1/namespaces/demo/pods?watch=true", "/api/v1/namespaces/demo/pods?limit=100&limit=100",
                "/api/v1/namespaces/demo/pods/web/log?container=app&tailLines=999999&limitBytes=4096",
                "/api/v1/namespaces/demo/events?fieldSelector=metadata.namespace%3Dother",
                "/api/v1/namespaces/demo/../other/pods", "/api/v1/namespaces/demo/pods#fragment"})
            assertThatThrownBy(()->ChatInspectionGraph.reviewRequest(path,"demo")).isInstanceOf(ContextMismatch.class);
    }
}
