package in.simplifymoney.ledgersync.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsTest {

    private NormalizedTxn txn(String acct, Direction dir, String amount, Category cat,
                               String merchant, String at, String... msgIds) {
        return new NormalizedTxn(acct, OffsetDateTime.parse(at), dir, new BigDecimal(amount),
                cat, merchant, List.of(msgIds));
    }

    @Test
    void summaryExcludesMicroAndTransferFromSpendAndIncome() {
        List<NormalizedTxn> ledger = List.of(
                txn("4821", Direction.DEBIT, "500.00", Category.SPEND, "AMAZON",
                        "2026-07-01T10:00:00+05:30", "m1"),
                txn("4821", Direction.CREDIT, "1000.00", Category.INCOME, "SALARY",
                        "2026-07-01T11:00:00+05:30", "m2"),
                txn("4821", Direction.DEBIT, "50.00", Category.MICRO, "UPI/CHAI",
                        "2026-07-01T12:00:00+05:30", "m3"),
                txn("4821", Direction.DEBIT, "8000.00", Category.TRANSFER, "IMPS/P2A/X",
                        "2026-07-01T13:00:00+05:30", "m4"),
                txn("4821", Direction.CREDIT, "2000.00", Category.TRANSFER, "IMPS/P2A/Y",
                        "2026-07-01T14:00:00+05:30", "m5"));

        Map<String, Object> summary = Reports.summary(ledger);
        @SuppressWarnings("unchecked")
        Map<String, Object> acct = (Map<String, Object>)
                ((Map<String, Object>) summary.get("accounts")).get("4821");

        assertEquals("500.00", acct.get("spend"));
        assertEquals("1000.00", acct.get("income"));
        assertEquals(1, acct.get("micro_count"));
        assertEquals("50.00", acct.get("micro_total"));
        assertEquals("8000.00", acct.get("transferred_out"));
        assertEquals("2000.00", acct.get("transferred_in"));
    }

    @Test
    void reconciliationDerivesOpeningBalanceAndFindsNoGapWhenTheChainIsComplete() {
        List<NormalizedTxn> ledger = List.of(
                txn("4821", Direction.DEBIT, "100.00", Category.SPEND, "A",
                        "2026-07-01T10:00:00+05:30", "m1"),
                txn("4821", Direction.CREDIT, "50.00", Category.INCOME, "B",
                        "2026-07-01T11:00:00+05:30", "m2"));
        // m1: balance after = 900 (implies opening 1000); m2: balance after = 950
        Map<String, BigDecimal> evidence = Map.of("m1", new BigDecimal("900.00"),
                "m2", new BigDecimal("950.00"));

        Map<String, Object> recon = Reports.reconciliation(ledger, evidence);
        @SuppressWarnings("unchecked")
        Map<String, Object> acct = (Map<String, Object>)
                ((Map<String, Object>) recon.get("accounts")).get("4821");

        assertEquals("reconciled", acct.get("status"));
        assertEquals("1000.00", acct.get("opening_balance"));
        assertEquals("950.00", acct.get("closing_balance_from_ledger"));
    }

    @Test
    void reconciliationReportsAGapWhenAMessageIsMissingEvidence() {
        // mirrors corpus-a's real 4821 gap: two known transactions with a
        // 7500.00 debit in between that no message evidences at all.
        List<NormalizedTxn> ledger = List.of(
                txn("4821", Direction.DEBIT, "899.99", Category.SPEND, "IRCTC",
                        "2026-07-29T11:53:00+05:30", "m1"),
                txn("4821", Direction.DEBIT, "75.00", Category.MICRO, "UPI/STATIONERY",
                        "2026-07-29T17:06:00+05:30", "m2"));
        Map<String, BigDecimal> evidence = Map.of(
                "m1", new BigDecimal("36054.05"),
                "m2", new BigDecimal("28479.05")); // the real 7500.00 gap is between these

        Map<String, Object> recon = Reports.reconciliation(ledger, evidence);
        @SuppressWarnings("unchecked")
        Map<String, Object> acct = (Map<String, Object>)
                ((Map<String, Object>) recon.get("accounts")).get("4821");

        assertEquals("discrepancies_found", acct.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) acct.get("discrepancies");
        assertEquals(1, discrepancies.size());
        assertEquals("7500.00", discrepancies.get(0).get("unaccounted_amount"));
    }

    @Test
    void anAccountWithNoBalanceEvidenceIsNotReconcilable() {
        // the credit-card account: every message only ever quotes a limit,
        // never a balance, so there is no balance evidence at all.
        List<NormalizedTxn> ledger = List.of(
                txn("3310", Direction.DEBIT, "1249.99", Category.SPEND, "BLINKIT",
                        "2026-07-03T11:51:00+05:30", "m1"));

        Map<String, Object> recon = Reports.reconciliation(ledger, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> acct = (Map<String, Object>)
                ((Map<String, Object>) recon.get("accounts")).get("3310");

        assertEquals("not_reconcilable", acct.get("status"));
    }
}
