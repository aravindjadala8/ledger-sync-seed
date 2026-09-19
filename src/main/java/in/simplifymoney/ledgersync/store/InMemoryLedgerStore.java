package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.identity.TransactionIdentity;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Used by SelfCheck and by tests. Keeps everything it is given. */
public final class InMemoryLedgerStore implements LedgerStore {

    private final List<NormalizedTxn> rows = new ArrayList<>();
    private final Map<TransactionIdentity, Integer> indexByIdentity = new LinkedHashMap<>();

    @Override
    public void save(NormalizedTxn txn) {
        rows.add(txn);
    }

    @Override
    public synchronized void upsert(NormalizedTxn txn) {
        TransactionIdentity id = TransactionIdentity.of(txn);
        Integer at = indexByIdentity.get(id);
        if (at == null) {
            indexByIdentity.put(id, rows.size());
            rows.add(txn);
            return;
        }
        NormalizedTxn existing = rows.get(at);
        TreeSet<String> merged = new TreeSet<>(existing.sourceMessageIds());
        merged.addAll(txn.sourceMessageIds());
        rows.set(at, new NormalizedTxn(existing.accountLast4(), existing.occurredAt(),
                existing.direction(), existing.amount(), existing.category(),
                existing.merchant(), merged.stream().toList()));
    }

    @Override public List<NormalizedTxn> all() { return Collections.unmodifiableList(rows); }

    @Override public long count() { return rows.size(); }
}
