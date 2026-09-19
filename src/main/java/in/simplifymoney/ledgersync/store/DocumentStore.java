package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The document store the ledger is moving to.
 *
 * ============================================================================
 * DESIGN (see the decision log for the full writeup and the corpus evidence
 * behind each choice). Target engine: DynamoDB. A single table, one item per
 * real transaction, addressed to serve exactly the three access patterns
 * below directly - no Scan, no filter expression, for any of them:
 *
 *   PK = "ACCT#<accountLast4>"
 *   SK = "TXN#<occurredAt ISO-8601, zero-padded>#<txnId>"
 *     -> serves Q1 directly: Query on PK with SK begins_with
 *        "TXN#<yyyy-MM>", ScanIndexForward=false. No filter, no scan.
 *
 *   GSI1: PK = "ACCT#<accountLast4>#CAT#<category>"
 *         SK = "TOTAL"
 *     -> a single running-total item per (account, category), maintained by
 *        an atomic ADD on every write (never recomputed by reading every
 *        transaction). Serves Q2 as at most 4 GetItems (one per Category),
 *        not a scan of the account's history.
 *
 *   GSI2: PK = "MSG#<sourceMessageId>"
 *         SK = "TXN"  (constant - a message id points at exactly one txn)
 *     -> serves Q3 directly: a transaction with N source messages has N
 *        GSI2 pointer rows, one per message id, each resolving to the same
 *        item. A single Query on GSI2, not a scan.
 *
 * Writes are idempotent by construction: save() is keyed by the same content
 * identity (TransactionIdentity) used everywhere else in this codebase, so
 * calling save() again with the same transaction (or an overlapping one)
 * merges rather than duplicates - this is what makes Backfill reruns safe.
 *
 * IMPLEMENTATION NOTE (environment limitation, stated plainly rather than
 * left implicit): this sandbox has no network access to AWS or to Maven
 * Central (only a short allow-list of package-registry/OS-update domains),
 * so an actual AWS SDK v2 DynamoDB client could not be written, compiled or
 * tested here - doing so would mean shipping untested code, which the
 * assignment explicitly asks not to do. InMemoryDocumentStore below
 * implements this exact interface with the exact same PK/SK/GSI shape and
 * the exact same idempotent-write semantics, backed by in-memory indexes
 * instead of DynamoDB API calls, and IS compiled and tested in this
 * environment (see DocumentStoreTest, BackfillTest, ConsistencyCheckerTest
 * and BenchmarkTest). Swapping in a real DynamoDbDocumentStore that
 * implements this same interface against the item shape specified above -
 * for example against DynamoDB Local via docker-compose (see
 * docker-compose.yml) - is the natural next step and is called out as
 * UNFINISHED in the README rather than claimed as done.
 * ============================================================================
 */
public interface DocumentStore {

    /** Q1: one account's transactions for one month, newest first. */
    List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month);

    /** Q2: running totals per category for an account, for its whole history. */
    Map<Category, BigDecimal> categoryTotals(String accountLast4);

    /** Q3: which transaction, if any, did this message produce? */
    Optional<NormalizedTxn> byMessageId(String messageId);

    /**
     * Insert, or merge into an existing item of the same content identity -
     * see TransactionIdentity. Idempotent: calling this twice with the same
     * or an overlapping transaction never creates a second item.
     */
    void save(NormalizedTxn txn);
}
