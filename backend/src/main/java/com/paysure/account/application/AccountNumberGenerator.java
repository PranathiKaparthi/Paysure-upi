package com.paysure.account.application;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Generates simulated 12-digit account numbers. Prefix 9 marks them as sandbox numbers. */
@Component
public class AccountNumberGenerator {

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder sb = new StringBuilder("9");
        for (int i = 0; i < 11; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
