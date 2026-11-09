package br.com.minhaobra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

final class PublicProjectionAccessFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 4096;
    private static final int PER_IP_LIMIT = 12;
    private static final int GLOBAL_LIMIT = 480;
    private final Clock clock;
    private final Map<String, Integer> counts = new HashMap<>();
    private long window = Long.MIN_VALUE;
    private int total;

    PublicProjectionAccessFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!allow(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "60");
            SecurityResponses.error(response, 429, "TOO_MANY_ATTEMPTS", "Aguarde um minuto para simular novamente.");
            return;
        }
        long length = request.getContentLengthLong();
        if (length < 0) {
            SecurityResponses.error(response, 411, "PROJECTION_LENGTH_REQUIRED", "Informe o tamanho da solicitação.");
            return;
        }
        if (length > MAX_BODY_BYTES) {
            SecurityResponses.error(response, 413, "PROJECTION_REQUEST_TOO_LARGE", "A simulação contém dados demais.");
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allow(String ip) {
        long current = clock.millis() / 60_000;
        if (current != window) {
            window = current;
            counts.clear();
            total = 0;
        }
        int count = counts.getOrDefault(ip, 0);
        if (total >= GLOBAL_LIMIT || count >= PER_IP_LIMIT) {
            return false;
        }
        counts.put(ip, count + 1);
        total++;
        return true;
    }
}
