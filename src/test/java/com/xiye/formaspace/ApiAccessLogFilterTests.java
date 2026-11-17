package com.xiye.formaspace;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xiye.formaspace.common.web.ApiAccessLogFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class ApiAccessLogFilterTests {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiAccessLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ApiAccessLogFilter filter = new ApiAccessLogFilter();

    @BeforeEach
    void captureLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.remove("requestId");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 401, 403, 500})
    void logsStatusWithoutReadingCredentialsAndRestoresThreadContext(int status) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setCharacterEncoding("UTF-8");
        request.setQueryString("password=query-secret");
        request.addHeader("Authorization", "Bearer header-secret");
        request.setContent("{\"password\":\"body-secret\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        MDC.put("requestId", "outer-context");
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id"));
            response.setStatus(status);
        });
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("method=POST", "path=/api/auth/login", "status=" + status, "durationMs=",
                        "requestId=" + response.getHeader("X-Request-Id"))
                .doesNotContain("query-secret", "header-secret", "body-secret", "password=");
        assertThat(request.getContentAsString()).contains("body-secret");
    }

    @Test
    void logsUnhandledFailureAs500WithoutExposingExceptionMessage() {
        var request = new MockHttpServletRequest("GET", "/api/health");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("internal-sensitive-message");
        })).isInstanceOf(ServletException.class);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("status=500").doesNotContain("internal-sensitive-message");
        assertThat(MDC.get("requestId")).isNull();
    }
}
