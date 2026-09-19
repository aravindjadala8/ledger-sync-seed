package in.simplifymoney.ledgersync.identity;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns each grouped transaction exactly one Category.
 *
 * Order matters and is deliberate:
 *
 *  1. TRANSFER - checked first, so a small self-transfer is never
 *     miscategorized as MICRO, and a self-transfer is never counted as
 *     ordinary SPEND/INCOME.
 *  2. MICRO - a UPI debit of Rs.100 or less.
 *  3. SPEND / INCOME - everything else, by direction.
 *
 * TRANSFER evidence (validated against the whole of corpus-a - see the
 * decision log): a transfer is a DEBIT on one of the user's accounts and a
 * CREDIT on ANOTHER of the user's accounts, for the exact same amount,
 * within a short window of each other. This is checked structurally - by
 * finding the matching opposite leg - never by pattern-matching the
 * merchant string alone. Merchant text such as "IMPS/P2A/<name>" is corpus
 * flavour, not the rule: corpus-a's "IMPS/P2A/RAHUL SHARMA" debit has the
 * same merchant *shape* as the real transfers but no opposite leg exists
 * anywhere in the corpus, and is correctly left as SPEND.
 *
 * The matching window (30 minutes) was chosen empirically: every one of
 * corpus-a's 5 real transfer pairs closes within ~2 minutes, and widening the
 * window all the way to 30 minutes across the ENTIRE corpus (235 unique
 * savings-account transactions) produces the exact same 5 pairs with zero
 * additional matches - i.e. no unrelated same-amount transaction on the two
 * accounts collides even with a generously wide window. The window exists to
 * bound the search, not to loosen the match: amount equality and opposite
 * direction across different accounts are exact, never fuzzy.
 *
 * MICRO evidence: corpus-a's checkpoint gives micro_count 52 (account 4821)
 * and 45 (account 9075). Filtering the corpus for "debit, merchant starts
 * with UPI, amount <= 100.00" reproduces both counts exactly. Amounts <=100
 * on non-UPI merchants (IRCTC 99.99, BIGBASKET 99.99, SWIGGY 47.33, DMART
 * 99.99, SPOTIFY 47.33, MYNTRA 62.67, ...) are excluded from that checkpoint
 * count, confirming amount alone is not sufficient - the UPI-merchant prefix
 * is a required part of the rule.
 */
public final class Categorizer {

    private static final BigDecimal MICRO_THRESHOLD = new BigDecimal("100.00");
    private static final Duration TRANSFER_WINDOW = Duration.ofMinutes(30);

    public void categorize(List<TxnGroup> groups) {
        markTransfers(groups);
        for (TxnGroup g : groups) {
            if (g.category() != null) continue; // already marked TRANSFER
            g.assignCategory(isMicro(g) ? Category.MICRO
                    : g.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME);
        }
    }

    private boolean isMicro(TxnGroup g) {
        return g.direction() == Direction.DEBIT
                && g.merchant() != null
                && g.merchant().trim().toUpperCase().startsWith("UPI")
                && g.amount().compareTo(MICRO_THRESHOLD) <= 0;
    }

    /**
     * Buckets candidates by exact amount first (so the search never compares
     * transactions of different amounts, which is the overwhelming majority
     * of pairs), then greedily pairs opposite-direction, different-account
     * legs within TRANSFER_WINDOW, nearest-in-time first. Each leg is used at
     * most once. This keeps the search close to O(n log n) rather than
     * O(n^2) over the whole ledger, which matters once the hidden corpus is
     * much larger than 522 messages.
     */
    private void markTransfers(List<TxnGroup> groups) {
        Map<BigDecimal, List<TxnGroup>> byAmount = new LinkedHashMap<>();
        for (TxnGroup g : groups) {
            byAmount.computeIfAbsent(g.amount(), k -> new ArrayList<>()).add(g);
        }

        for (List<TxnGroup> bucket : byAmount.values()) {
            if (bucket.size() < 2) continue;

            List<Candidate> pairs = new ArrayList<>();
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    TxnGroup a = bucket.get(i);
                    TxnGroup b = bucket.get(j);
                    if (a.direction() == b.direction()) continue;
                    if (a.accountLast4().equals(b.accountLast4())) continue;
                    Duration delta = Duration.between(a.occurredAt(), b.occurredAt()).abs();
                    if (delta.compareTo(TRANSFER_WINDOW) > 0) continue;
                    pairs.add(new Candidate(a, b, delta));
                }
            }
            pairs.sort((x, y) -> x.delta.compareTo(y.delta));

            java.util.Set<TxnGroup> used = new java.util.HashSet<>();
            for (Candidate c : pairs) {
                if (used.contains(c.a) || used.contains(c.b)) continue;
                c.a.assignCategory(Category.TRANSFER);
                c.b.assignCategory(Category.TRANSFER);
                used.add(c.a);
                used.add(c.b);
            }
        }
    }

    private record Candidate(TxnGroup a, TxnGroup b, Duration delta) {}
}
