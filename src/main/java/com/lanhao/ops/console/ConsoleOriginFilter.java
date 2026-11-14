package com.lanhao.ops.console;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** 控制台使用显式认证头，不持久化浏览器凭据；写请求还需同源自定义头。 */
public class ConsoleOriginFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/api/console/")&&!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
            boolean valid="1".equals(request.getHeader("X-Console-Request"));
            String origin=request.getHeader("Origin");
            if(origin!=null) {
                try {
                    URI uri=URI.create(origin);
                    valid&=Set.of("http","https").contains(uri.getScheme())&&uri.getRawAuthority()!=null
                            &&uri.getRawAuthority().equalsIgnoreCase(request.getHeader("Host"));
                }catch(RuntimeException e){valid=false;}
            }
            if(!valid){
                // 直接返回，避免 /error 二次派发将已拒绝的请求改成认证挑战。
                response.setStatus(403); response.setContentType("application/problem+json;charset=UTF-8");
                response.getWriter().write("{\"status\":403,\"detail\":\"控制台写请求必须同源且包含专用请求头\"}");
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
