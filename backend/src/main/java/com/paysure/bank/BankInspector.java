package com.paysure.bank;

import com.paysure.bank.BankContracts.BankSideView;
import java.util.Optional;

/**
 * Sandbox-only window into the bank's side of a transfer. A real bank would not expose this; the
 * Reliability Lab uses it to show the difference between what the bank did and what it reported.
 */
public interface BankInspector {

    Optional<BankSideView> inspect(String bankReference);
}
