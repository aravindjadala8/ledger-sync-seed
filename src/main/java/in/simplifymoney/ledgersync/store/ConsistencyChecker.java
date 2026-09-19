package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.identity.TransactionIdentity;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Row-count comparison is explicitly not enough (the assignment says so,
 * and it is easy to see why: swap one transaction's amount for another of
 * the same count and a row-count check sees nothing wrong). Instead: build
 * the same TransactionIdentity used everywhere else in this codebase for
 * every transaction in each store, then diff the two keyed maps directly -
 * missing keys, extra keys, and, for keys present in both, a field-by-field
 * comparison of everything content-identity does NOT already guarantee is
 * equal (category, and the full source_message_ids set - identity only
 * commits to account/direction/amount/merchant/occurred-minute).
 *
 * Because SQL rows for one real transaction can be split across more than
 * one row (see Backfill's javadoc), the SQL side is grouped by
 * TransactionIdentity the same way Backfill groups it, before comparison -
 * otherwise every un-backfilled duplicate SQL row would be misreported as a
 * "missing in documents" transaction that was in fact already merged.
 */
public final class ConsistencyChecker {

    private final LedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(LedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        Map<TransactionIdentity, NormalizedTxn> sqlByIdentity = groupSql();
        Map<TransactionIdentity, NormalizedTxn> docsByIdentity = allDocuments();

        List<Divergence> out = new ArrayList<>();

        for (var e : sqlByIdentity.entrySet()) {
            NormalizedTxn inSql = e.getValue();
            NormalizedTxn inDocs = docsByIdentity.get(e.getKey());
            if (inDocs == null) {
                out.add(new Divergence(describe(inSql), "MISSING_IN_DOCUMENTS",
                        describe(inSql), "(no matching item)"));
                continue;
            }
            compareFields(inSql, inDocs, out);
        }

        for (var e : docsByIdentity.entrySet()) {
            if (!sqlByIdentity.containsKey(e.getKey())) {
                NormalizedTxn inDocs = e.getValue();
                out.add(new Divergence(describe(inDocs), "EXTRA_IN_DOCUMENTS",
                        "(no matching row)", describe(inDocs)));
            }
        }

        return out;
    }

    private void compareFields(NormalizedTxn sqlTxn, NormalizedTxn docTxn, List<Divergence> out) {
        String key = describe(sqlTxn);
        if (sqlTxn.category() != docTxn.category()) {
            out.add(new Divergence(key, "FIELD_MISMATCH:category",
                    sqlTxn.category().name(), docTxn.category().name()));
        }
        if (!sqlTxn.accountLast4().equals(docTxn.accountLast4())) {
            out.add(new Divergence(key, "FIELD_MISMATCH:account_last4",
                    sqlTxn.accountLast4(), docTxn.accountLast4()));
        }
        if (sqlTxn.direction() != docTxn.direction()) {
            out.add(new Divergence(key, "FIELD_MISMATCH:direction",
                    sqlTxn.direction().name(), docTxn.direction().name()));
        }
        if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
            out.add(new Divergence(key, "FIELD_MISMATCH:amount",
                    sqlTxn.amount().toPlainString(), docTxn.amount().toPlainString()));
        }
        if (!sqlTxn.occurredAt().truncatedTo(ChronoUnit.SECONDS)
                .isEqual(docTxn.occurredAt().truncatedTo(ChronoUnit.SECONDS))) {
            out.add(new Divergence(key, "FIELD_MISMATCH:occurred_at",
                    sqlTxn.occurredAt().toString(), docTxn.occurredAt().toString()));
        }
        if (!sqlTxn.sourceMessageIds().equals(docTxn.sourceMessageIds())) {
            out.add(new Divergence(key, "FIELD_MISMATCH:source_message_ids",
                    sqlTxn.sourceMessageIds().toString(), docTxn.sourceMessageIds().toString()));
        }
    }

    private Map<TransactionIdentity, NormalizedTxn> groupSql() {
        Map<TransactionIdentity, NormalizedTxn> out = new LinkedHashMap<>();
        for (NormalizedTxn row : sql.all()) {
            TransactionIdentity id = TransactionIdentity.of(row);
            NormalizedTxn existing = out.get(id);
            if (existing == null) {
                out.put(id, row);
            } else {
                var ids = new java.util.TreeSet<>(existing.sourceMessageIds());
                ids.addAll(row.sourceMessageIds());
                out.put(id, new NormalizedTxn(existing.accountLast4(), existing.occurredAt(),
                        existing.direction(), existing.amount(), existing.category(),
                        existing.merchant(), ids.stream().toList()));
            }
        }
        return out;
    }

    private Map<TransactionIdentity, NormalizedTxn> allDocuments() {
        Map<TransactionIdentity, NormalizedTxn> out = new LinkedHashMap<>();
        if (documents instanceof InMemoryDocumentStore mem) {
            for (NormalizedTxn t : mem.all()) out.put(TransactionIdentity.of(t), t);
            return out;
        }
        // A real DynamoDB/Mongo implementation would need its own bulk scan
        // for this maintenance operation (this is the one place scanning
        // the whole store is legitimate: proving global consistency
        // necessarily requires seeing every item at least once, on both
        // sides). InMemoryDocumentStore exposes all() for exactly this.
        throw new UnsupportedOperationException(
                "ConsistencyChecker needs a way to enumerate every document; "
                        + "add that to whichever DocumentStore implementation is in use");
    }

    private static String describe(NormalizedTxn t) {
        return t.accountLast4() + " " + t.direction() + " " + t.amount().toPlainString()
                + " " + t.merchant() + " @" + t.occurredAt();
    }

    /** One place the two stores disagree. */
    public record Divergence(String transaction, String what, String inSql, String inDocuments) {
        @Override
        public String toString() {
            return what + " [" + transaction + "]: sql=" + inSql + " documents=" + inDocuments;
        }
    }
}
