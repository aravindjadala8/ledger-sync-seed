package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.identity.TransactionIdentity;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * The store this service has used since it was written: a single relational
 * table, reached over plain JDBC.
 *
 * The driver is a runtime dependency (see build.gradle) - this class compiles
 * against the JDK alone.
 */
public final class SqlLedgerStore implements LedgerStore, AutoCloseable {

    private static final String URL_PREFIX = "jdbc:h2:";
    private final Connection conn;

    public SqlLedgerStore(Path dbFile) {
        try {
            this.conn = DriverManager.getConnection(
                    URL_PREFIX + dbFile.toAbsolutePath() + ";MODE=PostgreSQL", "sa", "");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "could not open the ledger database at " + dbFile
                            + " (is the H2 driver on the runtime classpath?)", e);
        }
    }

    /** Applies every db/migration/V*.sql in filename order. */
    public void migrate(Path migrationDir) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_history ("
                    + "  filename VARCHAR(200) PRIMARY KEY,"
                    + "  applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

            List<Path> files;
            try (var s = Files.list(migrationDir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                try (PreparedStatement q = conn.prepareStatement(
                        "SELECT 1 FROM schema_history WHERE filename = ?")) {
                    q.setString(1, name);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) continue;
                    }
                }
                String sql = Files.readString(f);
                for (String stmt : sql.split(";")) {
                    if (!stmt.isBlank()) st.execute(stmt);
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO schema_history(filename) VALUES (?)")) {
                    ins.setString(1, name);
                    ins.executeUpdate();
                }
                System.out.println("applied " + name);
            }
        } catch (Exception e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    @Override
    public void save(NormalizedTxn t) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ledger(account_last4, occurred_at, direction, amount,"
                        + " category, merchant, source_message_ids)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, t.accountLast4());
            ps.setString(2, t.occurredAt().toString());
            ps.setString(3, t.direction().name());
            ps.setBigDecimal(4, t.amount());
            ps.setString(5, t.category().name());
            ps.setString(6, t.merchant());
            ps.setString(7, String.join(",", t.sourceMessageIds()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not save " + t, e);
        }
    }

    /**
     * Insert, or merge into an existing row with the same content identity
     * (see TransactionIdentity). Candidate rows are narrowed down in SQL by
     * the indexed, cheaply-comparable columns (account, direction, amount,
     * merchant); the final identity check (which also compares the
     * minute-truncated occurred_at) happens in Java against that small
     * candidate set, never against the whole table.
     */
    @Override
    public synchronized void upsert(NormalizedTxn txn) {
        TransactionIdentity incoming = TransactionIdentity.of(txn);
        try (PreparedStatement q = conn.prepareStatement(
                "SELECT id, occurred_at, source_message_ids FROM ledger"
                        + " WHERE account_last4 = ? AND direction = ? AND amount = ?"
                        + " AND merchant = ?")) {
            q.setString(1, txn.accountLast4());
            q.setString(2, txn.direction().name());
            q.setBigDecimal(3, txn.amount());
            q.setString(4, txn.merchant());
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime existingAt = OffsetDateTime.parse(rs.getString(2));
                    TransactionIdentity existingId = TransactionIdentity.of(
                            txn.accountLast4(), txn.direction(), txn.amount(),
                            txn.merchant(), existingAt);
                    if (!existingId.equals(incoming)) continue;

                    long id = rs.getLong(1);
                    TreeSet<String> merged = new TreeSet<>(Arrays.stream(
                            rs.getString(3).split(",")).filter(s -> !s.isBlank()).toList());
                    merged.addAll(txn.sourceMessageIds());
                    try (PreparedStatement u = conn.prepareStatement(
                            "UPDATE ledger SET source_message_ids = ? WHERE id = ?")) {
                        u.setString(1, String.join(",", merged));
                        u.setLong(2, id);
                        u.executeUpdate();
                    }
                    return;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not upsert " + txn, e);
        }
        save(txn);
    }

    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account_last4, occurred_at, direction, amount, category,"
                             + " merchant, source_message_ids FROM ledger ORDER BY occurred_at")) {
            while (rs.next()) {
                out.add(new NormalizedTxn(
                        rs.getString(1),
                        OffsetDateTime.parse(rs.getString(2)),
                        Direction.valueOf(rs.getString(3)),
                        rs.getBigDecimal(4).setScale(2),
                        Category.valueOf(rs.getString(5)),
                        rs.getString(6),
                        Arrays.stream(rs.getString(7).split(","))
                                .filter(s -> !s.isBlank()).toList()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the ledger", e);
        }
        return out;
    }

    @Override
    public long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the ledger", e);
        }
    }

    public BigDecimal sumAmounts() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT SUM(amount) FROM ledger")) {
            return rs.next() && rs.getBigDecimal(1) != null
                    ? rs.getBigDecimal(1).setScale(2) : BigDecimal.ZERO.setScale(2);
        } catch (SQLException e) {
            throw new IllegalStateException("could not total the ledger", e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }
}
