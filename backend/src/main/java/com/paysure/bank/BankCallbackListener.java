package com.paysure.bank;

import com.paysure.bank.BankContracts.Callback;

/**
 * Receives asynchronous bank callbacks. Delivery is at-least-once and unordered, so implementations
 * must be idempotent and must ignore stale callbacks.
 */
public interface BankCallbackListener {

    void onCallback(Callback callback);
}
