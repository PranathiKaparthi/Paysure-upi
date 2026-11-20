package com.paysure.support;

import com.paysure.reconciliation.ReconciliationModel.TriggerType;
import com.paysure.reconciliation.ReconciliationWorker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;

/** Shared helpers for reconciliation, lab and score tests. */
public abstract class ReliabilityTestBase extends PaymentTestBase {

    @Autowired
    protected ReconciliationWorker worker;

    /** The status-API outage switch is global; never leak it into other tests. */
    @AfterEach
    void restoreBankStatusApi() {
        bank.setStatusApiAvailable(true);
    }

    protected com.paysure.reconciliation.ReconciliationModel.RunSummary reconcile() {
        return worker.run(TriggerType.MANUAL);
    }

    protected List<Map<String, Object>> items(String paymentId) {
        return jdbc.queryForList("select * from reconciliation_items where payment_id = ?::uuid order by created_at, id", paymentId);
    }

    protected List<String> outcomes(String paymentId) {
        return items(paymentId).stream().map(i -> (String) i.get("outcome")).toList();
    }

    protected String paymentStatus(String paymentId) {
        return jdbc.queryForObject("select status from payments where id = ?::uuid", String.class, paymentId);
    }
}
