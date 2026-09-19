package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Document-store contract for the ledger migration.
 *
 * Target implementation: DynamoDB.
 *
 * Access patterns:
 *
 * Q1:
 * Account transactions for one month, newest first.
 *
 * Q2:
 * Running totals per category for an account.
 *
 * Q3:
 * Message ID to the transaction that produced it.
 *
 * The DynamoDB implementation uses:
 *
 *   Base table:
 *     PK = ACCT#<accountLast4>
 *     SK = TXN#<month>#<occurredAt>#<identityHash>
 *
 *   GSI1:
 *     GSI1PK = ACCT#<accountLast4>#CAT#<category>
 *     GSI1SK = TOTAL
 *
 *   GSI2:
 *     GSI2PK = MSG#<sourceMessageId>
 *     GSI2SK = TXN
 *
 * Transaction writes are keyed by TransactionIdentity so replaying the
 * same normalized transaction does not create another transaction item.
 */
public interface DocumentStore {

    /** Q1: one account's transactions for one month, newest first. */
    List<NormalizedTxn> forAccountMonth(
            String accountLast4,
            YearMonth month);

    /** Q2: running totals per category for an account. */
    Map<Category, BigDecimal> categoryTotals(
            String accountLast4);

    /** Q3: which transaction, if any, did this message produce? */
    Optional<NormalizedTxn> byMessageId(
            String messageId);

    /**
     * Insert a transaction into the document store.
     *
     * Implementations must preserve transaction identity and avoid creating
     * duplicate transaction documents when the same transaction is replayed.
     */
    void save(NormalizedTxn txn);
}