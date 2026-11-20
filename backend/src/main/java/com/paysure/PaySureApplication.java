package com.paysure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * PaySure — UPI Payment Reliability Lab.
 *
 * <p>A sandbox simulator. No real bank, NPCI, UPI PIN or money movement is involved.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaySureApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaySureApplication.class, args);
    }
}
