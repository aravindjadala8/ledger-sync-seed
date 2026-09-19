package in.simplifymoney.ledgersync.identity;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.TreeSet;

/**
 * One real transaction, still being assembled: every message identified as
 * evidence for it gets folded in here before a Category is decided and it is
 * turned into a (frozen) NormalizedTxn.
 *
 * Mutable and package-visible on purpose - this is scratch space used only
 * while grouping a batch of parsed messages, never stored or handed out.
 */
public final class TxnGroup {

    private final String accountLast4;
    private final Direction direction;
    private final BigDecimal amount;
    private final String merchant;
    private OffsetDateTime occurredAt;
    private boolean occurredAtIsSmsAnchored;
    private final TreeSet<String> sourceMessageIds = new TreeSet<>();
    private Category category;
    private BigDecimal statedBalance;

    public TxnGroup(String accountLast4, Direction direction, BigDecimal amount,
                     String merchant, OffsetDateTime occurredAt, boolean smsAnchored,
                     String firstMessageId) {
        this.accountLast4 = accountLast4;
        this.direction = direction;
        this.amount = amount;
        this.merchant = merchant;
        this.occurredAt = occurredAt;
        this.occurredAtIsSmsAnchored = smsAnchored;
        this.sourceMessageIds.add(firstMessageId);
    }

    /** Folds another message's evidence into this transaction. */
    public void addEvidence(String messageId, OffsetDateTime candidateOccurredAt,
                             boolean candidateIsSmsAnchored) {
        sourceMessageIds.add(messageId);
        // SMS-reported time is authoritative over an email's Date header proxy
        // (see EmailParser's javadoc); once SMS-anchored, never downgrade.
        if (candidateIsSmsAnchored && !occurredAtIsSmsAnchored) {
            this.occurredAt = candidateOccurredAt;
            this.occurredAtIsSmsAnchored = true;
        }
    }

    /**
     * Records the balance the bank quoted alongside this piece of evidence,
     * if any (see ParsedTxn.statedBalance). Used only by reconciliation, to
     * localize a discrepancy to the transaction after which the ledger's
     * running total first stops matching what the bank actually reported -
     * never persisted as part of the frozen NormalizedTxn.
     */
    public void noteStatedBalance(BigDecimal candidate, boolean candidateIsSmsAnchored) {
        if (candidate == null) return;
        if (statedBalance == null || (candidateIsSmsAnchored && !occurredAtIsSmsAnchored)) {
            statedBalance = candidate;
        }
    }

    public BigDecimal statedBalance() { return statedBalance; }

    public String accountLast4() { return accountLast4; }
    public Direction direction() { return direction; }
    public BigDecimal amount() { return amount; }
    public String merchant() { return merchant; }
    public OffsetDateTime occurredAt() { return occurredAt; }
    public TreeSet<String> sourceMessageIds() { return sourceMessageIds; }

    public void assignCategory(Category category) { this.category = category; }
    public Category category() { return category; }

    public NormalizedTxn toNormalizedTxn() {
        if (category == null) {
            throw new IllegalStateException("category not assigned for " + this);
        }
        return new NormalizedTxn(accountLast4, occurredAt, direction, amount, category,
                merchant, sourceMessageIds.stream().toList());
    }

    @Override
    public String toString() {
        return accountLast4 + " " + direction + " " + amount + " " + merchant
                + " @" + occurredAt + " evidence=" + sourceMessageIds;
    }
}
