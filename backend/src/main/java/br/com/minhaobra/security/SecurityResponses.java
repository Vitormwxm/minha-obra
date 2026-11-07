package br.com.minhaobra.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class SecurityResponses {
    private SecurityResponses() {}
    static void error(HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());response.setHeader("Cache-Control","no-store");
        // All arguments are fixed application constants, never user-controlled values.
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");
    }
}
