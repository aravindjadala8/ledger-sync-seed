package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.identity.TransactionIdentity;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Two things this has to cope with (both real, both seeded in
 * db/migration/V2__seed.sql on purpose):
 *
 *  - the SQL store has no uniqueness guarantee. The same real transaction
 *    can appear as more than one SQL row - sometimes as a literal duplicate
 *    row (same source_message_ids value inserted twice), sometimes as two
 *    different rows citing two different source messages for the one real
 *    transaction (e.g. the seed's triplicate SWIGGY 449.00 row: two rows
 *    cite "m-legacy-0001", a third cites "m-legacy-0002" - all one real
 *    transaction).
 *  - this will be run more than once, including after a partial failure.
 *
 * Both are handled the same way: SQL rows are first grouped in memory by
 * TransactionIdentity (never by their own primary key, never by
 * source_message_ids string equality) - collapsing every duplicate/triplicate
 * SQL row for one real transaction into a single merged NormalizedTxn before
 * anything is written to the document store. DocumentStore.save() is itself
 * idempotent (see its javadoc), so writing that merged result is safe to
 * repeat: a second run recomputes the exact same grouping from the SQL
 * store's current contents and re-saves it, which DocumentStore.save()
 * merges into the identical existing item rather than duplicating it. A
 * crash partway through a run simply leaves some items already
 * correctly written and some not yet started; rerunning finishes the rest
 * with no risk of double-counting the ones already done.
 */
public final class Backfill {

    private final LedgerStore source;
    private final DocumentStore target;

    public Backfill(LedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        var sqlRows = source.all();

        Map<TransactionIdentity, NormalizedTxn> merged = new LinkedHashMap<>();
        for (NormalizedTxn row : sqlRows) {
            TransactionIdentity id = TransactionIdentity.of(row);
            NormalizedTxn existing = merged.get(id);
            if (existing == null) {
                merged.put(id, row);
            } else {
                TreeSet<String> ids = new TreeSet<>(existing.sourceMessageIds());
                ids.addAll(row.sourceMessageIds());
                merged.put(id, new NormalizedTxn(existing.accountLast4(), existing.occurredAt(),
                        existing.direction(), existing.amount(), existing.category(),
                        existing.merchant(), ids.stream().toList()));
            }
        }

        long written = 0;
        long failed = 0;
        for (NormalizedTxn txn : merged.values()) {
            try {
                target.save(txn);
                written++;
            } catch (RuntimeException e) {
                // one bad item does not abort the whole backfill; it is
                // reported so a retry (safe - see above) can pick it up
                failed++;
            }
        }

        long duplicatesCollapsed = sqlRows.size() - merged.size();
        return new Result(sqlRows.size(), written, duplicatesCollapsed, failed);
    }

    /**
     * read: raw SQL rows seen this run (including duplicates).
     * written: distinct real transactions successfully saved to the
     *   document store this run (a rerun with nothing new to do reports the
     *   same written count again - see BackfillTest - since save() re-merges
     *   into the same existing items rather than being skipped outright).
     * duplicatesCollapsed: SQL rows that were folded into another row's
     *   transaction rather than becoming a separate document (read - the
     *   number of distinct transactions actually produced).
     * failed: items that could not be written this run.
     */
    public record Result(long read, long written, long duplicatesCollapsed, long failed) {
        @Override
        public String toString() {
            return "backfill: read=" + read + " written=" + written
                    + " duplicatesCollapsed=" + duplicatesCollapsed + " failed=" + failed;
        }
    }
}
