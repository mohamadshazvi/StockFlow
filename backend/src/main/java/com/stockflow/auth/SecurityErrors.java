package com.stockflow.auth;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class SecurityErrors {
    private SecurityErrors() {}
    static void write(HttpServletRequest request, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        // All strings are server constants except the server-generated UUID.
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"fieldErrors\":[],\"traceId\":\"" + request.getAttribute("traceId") + "\"}");
    }
}
