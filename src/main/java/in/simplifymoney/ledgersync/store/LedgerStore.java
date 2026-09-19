package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;

/**
 * Where transactions live.
 *
 * save() makes no uniqueness promise - saving the same transaction twice
 * results in two rows. upsert() is what IngestService and Backfill actually
 * use: it inserts a transaction, or, if one with the same content identity
 * (see TransactionIdentity) already exists, merges the new evidence into it
 * instead of creating a second row. This is the mechanism that makes
 * re-running ingestion or backfill idempotent - see each implementation.
 */
public interface LedgerStore {

    void save(NormalizedTxn txn);

    /**
     * Insert, or merge into an existing row with the same content identity.
     * Merging widens source_message_ids (union, de-duplicated) and keeps the
     * existing occurred_at/category - the incoming txn is assumed to
     * describe the same real transaction, possibly with additional evidence.
     */
    void upsert(NormalizedTxn txn);

    List<NormalizedTxn> all();

    long count();
}
