package com.paysure.common.error;

import java.net.URI;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the single error shape used by every PaySure endpoint and security filter. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://paysure.sandbox/errors/" + code.toLowerCase().replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
