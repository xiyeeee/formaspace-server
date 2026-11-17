package com.xiye.formaspace.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAccessLogFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        String previousId = MDC.get("requestId");
        long startedAt = System.nanoTime();
        boolean failed = true;
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            try {
                int status = failed ? 500 : response.getStatus();
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                String path = request.getRequestURI().replaceAll("[\\r\\n\\t]", "_");
                if (path.length() > 512) path = path.substring(0, 512);
                var level = status >= 500 ? org.slf4j.event.Level.ERROR
                        : status >= 400 ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.INFO;
                log.atLevel(level).log("接口请求 method={} path={} status={} durationMs={} requestId={}",
                        request.getMethod(), path, status, duration, requestId);
            } finally {
                if (previousId == null) MDC.remove("requestId");
                else MDC.put("requestId", previousId);
            }
        }
    }
}
