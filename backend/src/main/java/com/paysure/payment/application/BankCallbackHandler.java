package com.paysure.payment.application;

import com.paysure.bank.BankCallbackListener;
import com.paysure.bank.BankContracts.Callback;
import org.springframework.stereotype.Component;

/** Adapter: bank callbacks (at-least-once, unordered) → idempotent finalisation. */
@Component
public class BankCallbackHandler implements BankCallbackListener {

    private final PaymentFinalizer finalizer;

    public BankCallbackHandler(PaymentFinalizer finalizer) {
        this.finalizer = finalizer;
    }

    @Override
    public void onCallback(Callback callback) {
        finalizer.applyCallback(callback);
    }
}
