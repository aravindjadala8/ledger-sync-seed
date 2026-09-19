package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ledger.json, summary.json and reconciliation.json.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    // ----------------------------------------------------------- ledger.json

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<NormalizedTxn> sorted = ledger.stream()
                .sorted(Comparator.comparing(NormalizedTxn::occurredAt)
                        .thenComparing(NormalizedTxn::accountLast4))
                .toList();
        List<Object> rows = sorted.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    // ---------------------------------------------------------- summary.json

    /**
     * Per README: spend excludes MICRO and TRANSFER; income excludes
     * TRANSFER; MICRO is rolled up into micro_count/micro_total rather than
     * listed individually; TRANSFER legs are reported separately as
     * transferred_out/transferred_in and must not inflate spend or income.
     */
    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO, income = ZERO, microTotal = ZERO;
            BigDecimal transferredOut = ZERO, transferredIn = ZERO;
            int microCount = 0;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> { microTotal = microTotal.add(t.amount()); microCount++; }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    // ------------------------------------------------------ reconciliation.json

    /**
     * Reconciliation is a chronological balance-chain walk per account, not
     * a row-count or final-balance comparison.
     *
     * Method (see the decision log for the corpus evidence this is based
     * on): every SMS-derived transaction carries the balance the bank
     * quoted immediately after it (Avl Bal / BalAvl). Walking a ledger's
     * transactions in occurred_at order and re-deriving a running balance
     * from an opening balance will only match every one of those quoted
     * balances if the ledger is missing nothing and has invented nothing.
     * Where the running total and a quoted balance disagree, that
     * disagreement pinpoints - to the transaction - where the ledger's
     * account of events and the bank's account of events diverge; this is
     * the "cannot account for" case the assignment asks reconciliation.json
     * to surface, rather than silently forcing the numbers to match.
     *
     * Two callers are expected: reconcile(ledger, checkpointOpeningBalances)
     * for corpus-a where opening balances are known ahead of time (from
     * fixtures/corpus-a-totals.json), and reconcileWithoutCheckpoint(ledger)
     * for a run where no checkpoint is available (the hidden corpus, or the
     * document-store/consistency-checker paths) - see App/CLI wiring.
     *
     * Accounts with no available balance checkpoint (in corpus-a: the
     * credit-card account, which only ever quotes an available LIMIT, never
     * an opening/closing balance) are explicitly reported as
     * "not_reconcilable" with a reason, rather than a fabricated
     * opening balance of zero producing a meaningless "discrepancy".
     */
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger,
            Map<String, BigDecimal> statedBalanceBySourceMessageId) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        Map<String, List<NormalizedTxn>> byAccount = new TreeMap<>();
        for (NormalizedTxn t : ledger) {
            byAccount.computeIfAbsent(t.accountLast4(), k -> new ArrayList<>()).add(t);
        }

        for (Map.Entry<String, List<NormalizedTxn>> e : byAccount.entrySet()) {
            String acct = e.getKey();
            List<NormalizedTxn> txns = e.getValue().stream()
                    .sorted(Comparator.comparing(NormalizedTxn::occurredAt)).toList();

            Map<String, Object> a = new LinkedHashMap<>();

            // Opening balance is derived, never hardcoded: it is the balance
            // implied by working backwards from the FIRST transaction in
            // this ledger for which the bank actually quoted a balance
            // alongside it. An account that never has balance evidence at
            // all (in corpus-a: the credit-card account, which only ever
            // quotes an available LIMIT - see HdfcSmsParser - never an
            // account balance) is explicitly not reconcilable, rather than
            // silently assuming an opening balance of zero.
            BigDecimal opening = null;
            for (NormalizedTxn t : txns) {
                BigDecimal stated = statedBalanceBySourceMessageId.get(t.sourceMessageIds().get(0));
                if (stated != null) {
                    opening = t.direction() == Direction.DEBIT
                            ? stated.add(t.amount()) : stated.subtract(t.amount());
                    break;
                }
            }
            if (opening == null) {
                a.put("status", "not_reconcilable");
                a.put("reason", "no balance evidence is available for this account - every "
                        + "source message for it is silent on the account balance (e.g. a "
                        + "credit-card account that only ever quotes an available limit, "
                        + "never an opening/closing balance)");
                a.put("transaction_count", txns.size());
                accounts.put(acct, a);
                continue;
            }

            List<Object> discrepancies = new ArrayList<>();
            BigDecimal running = opening;
            for (NormalizedTxn t : txns) {
                running = t.direction() == Direction.DEBIT
                        ? running.subtract(t.amount()) : running.add(t.amount());

                BigDecimal stated = statedBalanceBySourceMessageId.get(t.sourceMessageIds().get(0));
                if (stated != null && stated.compareTo(running) != 0) {
                    BigDecimal gap = stated.subtract(running).abs();
                    String likelyDirection = stated.compareTo(running) < 0
                            ? "an unrecorded debit (ledger balance is higher than the bank's)"
                            : "an unrecorded credit (ledger balance is lower than the bank's)";
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("account_last4", acct);
                    d.put("after_transaction_occurred_at", t.occurredAt().toString());
                    d.put("after_transaction_source_message_ids", t.sourceMessageIds());
                    d.put("ledger_running_balance", running.toPlainString());
                    d.put("bank_stated_balance", stated.toPlainString());
                    d.put("unaccounted_amount", gap.toPlainString());
                    d.put("likely_cause", likelyDirection);
                    d.put("note", "the ledger's running balance no longer matches the balance "
                            + "the bank quoted after this transaction; the gap most likely "
                            + "means a real transaction occurred in this window that no "
                            + "source message evidences, so it cannot be added to ledger.json "
                            + "without fabricating a source message");
                    discrepancies.add(d);
                    running = stated; // resync so one unexplained gap is reported once, not
                                       // re-flagged on every subsequent transaction
                }
            }

            a.put("status", discrepancies.isEmpty() ? "reconciled" : "discrepancies_found");
            a.put("opening_balance", opening.toPlainString());
            a.put("closing_balance_from_ledger", running.toPlainString());
            a.put("transaction_count", txns.size());
            a.put("discrepancies", discrepancies);
            accounts.put(acct, a);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    /** Convenience overload when no balance evidence is available at all. */
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        return reconciliation(ledger, Map.of());
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
