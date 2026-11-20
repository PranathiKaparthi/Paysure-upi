package com.paysure.bank;

import com.paysure.bank.BankContracts.StatusResult;
import com.paysure.bank.BankContracts.SubmitResult;
import com.paysure.bank.BankContracts.TransferInstruction;

/**
 * Port to the (simulated) bank / switch. Implementations must guarantee:
 * <ul>
 *   <li>{@link #submit} is idempotent per {@code bankReference}: re-submitting a reference never
 *       commits a second transfer — it reports the outcome of the first.</li>
 *   <li>{@link #queryStatus} never initiates a transfer.</li>
 * </ul>
 * PaySure itself never re-submits an attempt whose outcome is ambiguous; it queries status instead.
 */
public interface BankGateway {

    SubmitResult submit(TransferInstruction instruction);

    /** @throws BankContracts.BankUnreachableException if the status cannot be determined right now */
    StatusResult queryStatus(String bankReference);
}
