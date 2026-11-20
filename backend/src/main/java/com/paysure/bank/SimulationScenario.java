package com.paysure.bank;

/**
 * Deterministic behaviours of the simulated bank. Each describes BOTH what the bank actually commits
 * and what it reports back to PaySure, because in real payment systems the two can differ.
 *
 * <table>
 *   <tr><th>Scenario</th><th>Bank actually commits</th><th>Synchronous response</th><th>Callbacks</th></tr>
 *   <tr><td>NORMAL_SUCCESS</td><td>SUCCESS</td><td>SUCCESS</td><td>none</td></tr>
 *   <tr><td>INSUFFICIENT_FUNDS</td><td>FAILED</td><td>DECLINED</td><td>none</td></tr>
 *   <tr><td>BANK_TIMEOUT</td><td>FAILED (never debited)</td><td>TIMEOUT</td><td>none</td></tr>
 *   <tr><td>TIMEOUT_AFTER_COMMIT</td><td>SUCCESS</td><td>TIMEOUT (acknowledgement lost)</td><td>none</td></tr>
 *   <tr><td>BANK_UNAVAILABLE</td><td>nothing (request refused)</td><td>UNAVAILABLE</td><td>none</td></tr>
 *   <tr><td>NETWORK_FAILURE</td><td>nothing (request never arrived)</td><td>NETWORK_ERROR</td><td>none</td></tr>
 *   <tr><td>DELAYED_SUCCESS</td><td>SUCCESS after delay</td><td>ACCEPTED</td><td>SUCCESS once</td></tr>
 *   <tr><td>DELAYED_FAILURE</td><td>FAILED after delay</td><td>ACCEPTED</td><td>FAILED once</td></tr>
 *   <tr><td>DUPLICATE_CALLBACK</td><td>SUCCESS after delay</td><td>ACCEPTED</td><td>SUCCESS × N</td></tr>
 *   <tr><td>OUT_OF_ORDER_CALLBACK</td><td>SUCCESS after delay</td><td>ACCEPTED</td><td>SUCCESS(seq 2) then stale PROCESSING(seq 1)</td></tr>
 * </table>
 */
public enum SimulationScenario {
    NORMAL_SUCCESS,
    INSUFFICIENT_FUNDS,
    BANK_TIMEOUT,
    TIMEOUT_AFTER_COMMIT,
    BANK_UNAVAILABLE,
    NETWORK_FAILURE,
    DELAYED_SUCCESS,
    DELAYED_FAILURE,
    DUPLICATE_CALLBACK,
    OUT_OF_ORDER_CALLBACK
}
