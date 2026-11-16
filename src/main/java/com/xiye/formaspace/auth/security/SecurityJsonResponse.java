package com.xiye.formaspace.auth.security;

import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SecurityJsonResponse {
    private final ObjectMapper json;

    public void write(HttpServletResponse response, ErrorCode error) throws IOException {
        response.setStatus(error.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (error == ErrorCode.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(response.getOutputStream(), ApiResponse.failure(error.name(), error.getMessage()));
    }
}
