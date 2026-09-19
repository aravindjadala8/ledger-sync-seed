# ledger-sync

Turns bank SMS/email notifications uploaded from a phone into a trustworthy
financial ledger: `ledger.json`, `summary.json`, `reconciliation.json`.

This README is written to get a reviewer running and oriented in under five
minutes, and to be honest about exactly what has and hasn't been run in the
environment this was built in.

---

## 1. Prerequisites

- JDK 21 (only, for `verify.sh` — no network, no database, no Gradle needed)
- For the full CLI (`migrate` / `ingest` / `report` / `backfill` /
  `consistency-check`): a way to get the H2 JDBC driver onto the runtime
  classpath — either Gradle resolving it from Maven Central, or the
  Dockerfile's `curl` step (see §4). **Neither of these was reachable in the
  sandbox this was built in** — see §10, "Environment limitations
  encountered" — so those five commands are implemented and unit-tested
  against the same interfaces, but not exercised end-to-end here.

## 2. One-command startup (what was actually run)

```
./verify.sh
```

Needs nothing but a JDK. Compiles the whole `src/main/java` tree and runs
`SelfCheck`, which ingests `fixtures/corpus-a.jsonl` end-to-end (parse →
dedup → categorize → in-memory ledger) and prints the same category/account
breakdown as the real pipeline. This **was** run repeatedly while building
this, most recently with this output:

```
INGEST
  messages read       522
  transactions written 256
  messages skipped    43

BY CATEGORY
  SPEND        142567.64
  INCOME       142791.16
  MICRO          4443.85
  TRANSFER      62000.00

AGAINST fixtures/corpus-a-totals.json
  transactions   expected 257, produced 256
  **4821  txns 145 (expected 146)
           balance from ledger 48626.34, bank says 41126.34, difference 7500.00
  **9075  txns 91 (expected 91)
           balance from ledger 51210.63, bank says 51210.63, difference 0.00
```

The 4821/146-vs-145 gap is not a bug — see §8.

## 3. The full CLI (needs the H2 driver on the classpath — see §10)

```
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
./gradlew run --args="backfill"
./gradlew run --args="consistency-check"
```

`report` writes `submission/ledger.json`, `submission/summary.json`,
`submission/reconciliation.json`. `backfill` and `consistency-check` operate
against `InMemoryDocumentStore` (see §9) persisted to
`data/document-store.json` between CLI invocations.

## 4. Docker

```
docker compose up --build migrate ingest report
docker compose up --build backfill consistency-check
```

Also includes a `dynamodb-local` service for the DynamoDB design in §9. See
the top-of-file comments in `Dockerfile`/`docker-compose.yml`: **written but
not built or run** in this sandbox (no Docker daemon, no network to Maven
Central there — §10). Please verify before relying on it.

## 5. Tests

```
./gradlew test
```

**Could not be run in this sandbox** — there is no committed Gradle wrapper
and no network path to Maven Central to fetch one or to resolve JUnit (see
§10). What was actually done instead, and is fully reproducible:

- 59 test methods were written across 12 test classes (parsers, dedup,
  categorization, reports/reconciliation, ingest idempotency, document
  store, backfill, consistency checker — full list in §11), using real
  `org.junit.jupiter.api` imports, so they are ready for `./gradlew test`
  in an environment with network.
- To actually execute them here, a ~60-line local shim implementing the
  handful of JUnit annotations/assertions these tests use
  (`@Test`, `@DisplayName`, `assertEquals`, `assertTrue`, `assertFalse`,
  `assertNotNull`, `assertThrows`) was written, plus a tiny reflection-based
  runner. The test **source files themselves are unmodified, real JUnit 5
  code** — the shim only stands in for the dependency this sandbox can't
  download.
- Result, last run: **59/59 passed**, including the two frozen
  `NormalizedTxnContractTest`/`AmountsTest` files (extended, not modified,
  in `AmountsTest`'s case — one whole-rupee regression case added).

## 6. Verification

`./verify.sh` (§2) is the always-available check. `SelfCheck.java` compares
the ingest result against `fixtures/corpus-a-totals.json` and prints exactly
where it does and doesn't match — see §8 for what the one remaining gap is
and why it's correct, not a bug.

## 7. Incident: INC-2026-09-11 (the ₹92,213.10 water can)

**Root cause.** `Amounts.first(String)` matched rupee figures with the
pattern `(?:Rs\.?|INR)\s*([0-9,]+\.[0-9]{2})` — **exactly two decimal
digits, required**. A whole-rupee amount with no paise (`Rs.5`) simply did
not match. Since `first()` scanned the *whole message body* for the first
match, `"Rs.5 debited ... Avl Bal: Rs.92,213.10"` skipped the real amount
entirely and matched the balance instead.

**Blast radius.** Not water-can-specific: any whole-rupee amount, in any of
the four message formats, was at risk whenever a balance/limit figure
followed it in the same body. Pattern-matching the corpus for this shape
(independent of the buggy code) found **~38 affected messages** across HDFC
v1/v2 and both ICICI formats.

**Why the existing test suite stayed green.** `AmountsTest`'s five cases all
used amounts that already carried two decimal digits. Nothing exercised a
whole-rupee amount, so nothing caught it.

**The fix has two parts, both necessary:**
1. `Amounts`'s regex now makes the fractional part optional:
   `([0-9,]+(?:\.[0-9]{1,2})?)`.
2. **Structural fix, not just a wider regex**: `HdfcSmsParser`,
   `IciciSmsParser` and `EmailParser` no longer call `Amounts.first()`
   against the whole message body at all. Each format's own regex now
   captures the amount as a named group *from within its own transaction
   clause*. There is no "scan the whole body for the first rupee figure"
   step left to get confused by a balance/limit mentioned later in the same
   message — this holds regardless of what other numbers appear in the
   body, and regardless of whether the real amount has paise or not.
   `Amounts.first()` is kept only as a narrow fallback, not on the primary
   path for any of the four implemented formats.

**Regression tests** (`HdfcSmsParserTest#incidentRegression_...`,
`IciciSmsParserTest#incidentRegression_...`) feed the parsers the exact
water-can body and its ICICI-v2 sibling (`Dr INR 5 on ...; ... BalAvl Rs
52,841.30`) and assert the extracted amount is `5.00`. Both fail against the
pre-fix code (confirmed by temporarily reverting the regex while writing
these) and pass against the fix.

**Five-line incident summary:**
```
What broke: a debit of Rs.5 was recorded and displayed as Rs.92,213.10.
How found: user complaint + a balance-divergence alert on account 4821.
Affected: any message whose true amount had no paise and was followed
  by a balance/limit figure in the same body - ~38 messages in corpus-a,
  not just the one water-can example.
Root cause: amount extraction required two decimal digits, so whole-rupee
  amounts fell through to matching the balance instead.
Guard added: amount is now extracted from within each format's own
  transaction-clause regex, never from a whole-body scan; two regression
  tests assert Rs.5 (not the balance) for both affected message formats.
```

## 8. Reconciliation: why account 4821 has 145, not 146, ledger transactions

This was investigated thoroughly (not waved away) before writing any
production code — see the decision log (§13, entries 1–2).

Walking account 4821's balance chain in true occurred-time order (opening
₹48,211.40 → closing ₹41,126.34), using **only messages that exist in the
corpus**, breaks in exactly two places:

- **2026-07-22, +₹649.00** — a NETFLIX debit evidenced *only* by an email
  (no SMS exists for it at all). This **is** captured — it becomes
  ledger transaction #145.
- **2026-07-29, between 11:53 and 17:06, +₹7,500.00** — `m-00203-bbc8db`
  (11:53, balance ₹36,054.05) is the last point where the chain matches;
  `m-00204-4741e7` (17:06, balance ₹28,479.05, amount ₹75.00) is the next.
  `36,054.05 − 7,500.00 − 75.00 = 28,479.05` exactly. **No message of any
  kind — SMS or email — evidences this debit.** A full-text search of the
  entire corpus for `7500`/`7,500` returns zero matches.

`transactions_expected: 146` counts the real-world event; the corpus
withholds its evidence on purpose. `NormalizedTxn` requires a non-empty
`sourceMessageIds` — there is no way to construct a 146th ledger row without
fabricating a source message, which the assignment explicitly forbids.
Account 9075's chain, by contrast, closes with **zero** gaps and its
checkpoint total (91) matches exactly — this both confirms the balance-chain
method itself is sound (it isn't systematically prone to false gaps) and
that 4821's gap is real, not a bug in the check.

`reconciliation.json`'s job is exactly to surface this: for account 4821 it
reports a discrepancy naming the account, the bracketing transaction, the
ledger/bank balances, and the ₹7,500.00 unaccounted amount — see
`Reports.reconciliation` and `ReportsTest`.

## 9. Document store

**Design target: DynamoDB.** Single table, one item per real transaction:

```
Table:  PK = ACCT#<accountLast4>          SK = TXN#<occurredAt ISO>#<txnId>
          -> Q1 (account+month, newest first): Query on PK, SK begins_with
             TXN#<yyyy-MM>, ScanIndexForward=false. No filter, no scan.

GSI1:   PK = ACCT#<accountLast4>#CAT#<category>   SK = TOTAL
          -> a single running-total item per (account, category), updated
             by an atomic ADD on every write, never recomputed by reading
             every transaction. Serves Q2 as up to 4 GetItems.

GSI2:   PK = MSG#<sourceMessageId>                SK = TXN
          -> Q3: a transaction with N source messages has N GSI2 pointer
             rows, all resolving to the same item. One Query, not a scan.
```

Writes are idempotent by construction: `save()` is keyed by the same content
identity (`TransactionIdentity`) used everywhere else in this codebase, so
saving the same or an overlapping transaction again merges rather than
duplicates — this is what makes `Backfill` reruns safe.

**Why not built against real DynamoDB here.** This sandbox has no network
path to AWS or to Maven Central (a short allow-list of package-registry/OS
domains only) — writing an AWS SDK v2 client here would mean shipping
untested code, which the assignment explicitly says not to do.
`InMemoryDocumentStore` implements the *exact same interface*, with the
*exact same PK/SK/GSI shape* (three separate internal indexes, kept in sync
on every write) and the *exact same idempotent-write semantics* — and **is**
compiled and tested here (`InMemoryDocumentStoreTest`, `BackfillTest`,
`ConsistencyCheckerTest`, `QueryBenchmark`). Swapping in a real
`DynamoDbDocumentStore` against the item shape above (e.g. against
`docker-compose.yml`'s `dynamodb-local` service) is the concrete next step —
listed as **UNFINISHED** in §15, not claimed as done.

**The three required queries, measured at 100,000 transactions**
(`QueryBenchmark`, run against `InMemoryDocumentStore`; see its javadoc for
exactly what "examined" means in this stand-in and why it wasn't measured
against a real DynamoDB table):

| Query | Examined | Returned |
|---|---|---|
| Q1 — one account's month, newest first | 2,116 | 2,116 |
| Q2 — category running totals for an account | 4 | 4 |
| Q3 — transaction for a given message id | 1 | 1 |

Examined == returned for all three: none of them scan the store.

## 10. Environment limitations encountered (stated plainly, not glossed over)

This sandbox's network egress allow-list covers OS package mirrors and a
handful of language-package registries (npm, pip, crates, GitHub) — **not**
Maven Central, **not** AWS. Concretely, this meant:

- The repository ships no Gradle wrapper, and none could be generated
  (no `gradle` binary, no network to fetch one).
- `./gradlew test` and any Gradle-resolved dependency (H2, JUnit, an AWS
  SDK) could not be exercised. §5 explains exactly what was done instead
  (a same-API-surface local shim + reflection runner) and why that is a
  faithful proxy for "did these JUnit tests pass", without pretending
  `./gradlew test` itself was run.
- `SqlLedgerStore` needs the H2 driver on the runtime classpath; without it,
  `migrate`/`ingest`/`report`/`backfill`/`consistency-check` cannot even
  construct a `SqlLedgerStore` here. `Backfill`/`ConsistencyChecker` were
  therefore deliberately written against the `LedgerStore` **interface**,
  not the concrete SQL class, specifically so they could be exercised here
  against `InMemoryLedgerStore` — this is also just better dependency
  hygiene independent of the sandbox constraint.
- No Docker daemon was available, so `Dockerfile`/`docker-compose.yml` are
  written (and the Dockerfile's H2-download step *would* work in a normal
  networked build) but not build/run-verified.
- A real DynamoDB/Mongo client could not be written and tested for the same
  network reason — see §9.

None of this is a reason the assignment's numbers should be trusted less:
every claim of "passes"/"produces X" in this README was actually executed
in this sandbox (`verify.sh`, the shim-run test suite, `QueryBenchmark`).
Where something could not be executed here, it says so, rather than
claiming success it can't back up.

## 11. Test suite (59 methods, 12 classes — all run via the shim, §5)

`AmountsTest` (+1 whole-rupee case), `NormalizedTxnContractTest` (frozen),
`HdfcSmsParserTest`, `IciciSmsParserTest`, `EmailParserTest`,
`DeduplicatorTest`, `CategorizerTest`, `ReportsTest`, `IngestServiceTest`,
`InMemoryDocumentStoreTest`, `BackfillTest`, `ConsistencyCheckerTest`.
Covers: every parser format including both incident-shape regressions,
noise/hostile/future-dated exclusion, whole-rupee amounts, exact re-upload
replay, cross-channel SMS/email linking (including the 9-day-gap and
recurring-merchant disambiguation cases), same-amount-different-transaction
non-collision, TRANSFER/MICRO/SPEND/INCOME boundaries (including the
Rahul-Sharma-stays-SPEND and reversal-is-two-transactions cases),
summary/reconciliation math, ingest idempotency (same corpus twice,
overlapping corpus, simulated partial-failure retry), all three document
store queries, backfill duplicate-collapse/rerun/resume, and consistency-
checker detection of missing/extra/mutated documents.

## 12. Architecture

```
RawMessage --Parsers--> ParsedTxn (or nothing, if not a transaction)
          --Deduplicator--> TxnGroup (evidence merged, content-identity keyed)
          --Categorizer--> TxnGroup + Category
          --LedgerStore.upsert--> NormalizedTxn (frozen)

Reports.{ledgerDocument,summary,reconciliation} read a LedgerStore's
contents (plus, for reconciliation, a small balance-evidence side-map - see
decision log entry 6) and produce the three JSON files.

Backfill:  LedgerStore  --(group by TransactionIdentity)--> DocumentStore
ConsistencyChecker: LedgerStore vs DocumentStore, keyed the same way.
```

`in.simplifymoney.ledgersync.identity` is new: `TransactionIdentity` (the
content-derived key used everywhere — never `messageId`/`receivedAt`),
`TxnGroup` (mutable scratch space for one transaction being assembled),
`Deduplicator`, `Categorizer`.

## 13. Decision log

1. **Transaction identity excludes `messageId`/`receivedAt` entirely.**
   Evidence: corpus-a's 2026-08-15 block is 167 messages, each byte-for-byte
   identical to an earlier message, uploaded under a new id/time. Any
   identity scheme using either field would double-count every one of them.
   Alternative rejected: using `messageId` as identity (the seed's original
   1:1 approach) — explicitly the thing the assignment warns against.

2. **The 4821 145-vs-146 gap is reported, not forced.** Investigated via a
   chronological balance-chain walk rather than accepting the checkpoint
   number at face value or loosening dedup until the count matched (which
   was explicitly forbidden and, more importantly, would have been
   dishonest — see §8). Alternative rejected: treating it as a dedup bug and
   widening the identity key's tolerance until 146 came out — this would
   have silently merged unrelated transactions on the hidden corpus.

3. **TRANSFER evidence is a matched opposite leg, not a merchant-string
   pattern.** Evidence: `IMPS/P2A/RAHUL SHARMA` has the identical merchant
   *shape* as the five real `IMPS/P2A/PARAG KAPOOR` transfers but no
   opposite leg anywhere in the corpus — regex-matching `IMPS/P2A` would
   misclassify it as TRANSFER. The chosen rule (opposite direction, exact
   amount, different account, within a window) was validated by widening
   the window to 30 minutes across the *entire* corpus (235 unique
   savings-account transactions) and confirming it produces exactly the
   same 5 pairs, zero extra matches — the window bounds the search, it
   doesn't loosen the match.

4. **MICRO requires the UPI-merchant prefix, not just amount ≤ ₹100.**
   Evidence: filtering the corpus for "debit, UPI-prefixed merchant, amount
   ≤ 100" reproduces the checkpoint's `micro_count` (52/45) exactly; several
   non-UPI purchases ≤ ₹100 (IRCTC 99.99, DMART 99.99, SWIGGY 47.33, ...)
   are correctly excluded from that count. Amount alone would have
   overcounted MICRO by roughly 30 transactions.

5. **Amount extraction moved from a whole-body scan into each format's own
   regex.** See §7. Rejected alternative: just widening `Amounts.first`'s
   regex and leaving parsers calling it against the raw body — this would
   still be vulnerable to any future format where a balance/limit precedes
   the amount textually; the chosen fix removes the whole-body-scan step
   for the four implemented formats entirely.

6. **A small non-frozen "balance evidence" side-file, not a schema change,
   carries per-message stated balances through to `report` time.**
   `NormalizedTxn`/`LedgerStore` are frozen/near-frozen and don't carry a
   stated balance. Precise reconciliation (pinpointing *which* transaction
   a gap follows, not just a final balance mismatch) needs it. Rejected
   alternatives: adding a column to the frozen-adjacent ledger table (more
   invasive, couples reconciliation to the SQL schema) and re-parsing raw
   messages at report time (couples `report` to the raw corpus, which a
   real deployment wouldn't want to re-read).

7. **`Backfill`/`ConsistencyChecker` depend on the `LedgerStore` interface,
   not `SqlLedgerStore`.** Forced by this sandbox's inability to construct
   a working `SqlLedgerStore` at all (no H2 driver reachable — §10), but a
   correct decision independent of that: it's what let both classes be
   compiled *and tested* here, and it's better dependency hygiene besides.

8. **No framework.** The seed already avoids one; nothing implemented here
   needed one either (H2 is the only runtime dependency; JUnit the only
   test one). Introducing Spring Boot or similar would have added Docker
   image size, startup time and dependency-resolution surface for no
   corresponding benefit at this scope.

9. **DynamoDB (design) over MongoDB, even though only an in-memory stand-in
   could be tested here.** The three access patterns are all
   exact-key/prefix lookups (account+month, account+category,
   message-id-exact) with no need for MongoDB's richer query/aggregation
   model; DynamoDB's PK/SK/GSI model maps onto them directly and cheaply.
   Rejected: MongoDB — would work too, but its extra query flexibility is
   unused here and its compound-index design is more to get precisely right
   for the exact same three patterns.

10. **`InMemoryDocumentStore` persists to a flat JSON file between CLI
    invocations**, rather than requiring `backfill`/`consistency-check`/
    `report` to run in one process. Matches the existing CLI's own
    process-per-command shape (`App.java`'s `migrate`/`ingest`/`report` are
    already separate invocations sharing only the H2 file) rather than
    introducing a different lifecycle just for the document-store commands.

## 14. AI disclosure

AI (this conversation) was used throughout: repository/corpus analysis,
regex/parser design, the identity/dedup/categorization design, the
reconciliation balance-chain method, Java implementation, the JUnit-shim
test-running approach, and this documentation.

**One concrete case where the AI's first approach was wrong, and what
corrected it:** the first draft of `Deduplicator`'s cross-channel (SMS/email)
linking used a fixed time window (initially reasoned as "email arrives ~3
hours after the SMS, so bound the match to a few hours") before any code was
written. Checking that assumption against the *actual* corpus surfaced a
legitimate SMS/email pair for the same real transaction (₹2,750.00, APOLLO
PHARMACY, account 9075) **9 days apart** — the only SMS and the only email
at that account/amount/merchant, so unambiguously the same transaction
despite the gap. A fixed window would have silently failed to link them,
creating a spurious extra ledger row. The design was corrected to match on
content (account, direction, amount, merchant) with **no hard time
cutoff**, using time only to disambiguate when a merchant/amount recurs
(needed separately, since IRCTC ~₹99.99 and similar amounts do recur on the
same account many times in the corpus). This is why the corpus analysis was
done *before* any `Deduplicator` code was written, and why the corpus, not
an initial assumption about how banks typically batch notifications, is
cited as evidence throughout the decision log.

## 15. Unfinished / explicitly not done

- **`DynamoDbDocumentStore`** (real AWS SDK v2 implementation against the
  PK/SK/GSI design in §9) — not written. `InMemoryDocumentStore` implements
  the identical interface and index shape and is what's tested. Blocked on
  this sandbox's lack of AWS/Maven-Central network access, not on the
  design being incomplete.
- **`./gradlew test`, actual Gradle-resolved run** — not run; see §5/§10 for
  what was run instead and why it's a faithful substitute for "these tests
  pass", not a claim that Gradle itself was exercised.
- **Docker build/run verification** — not run; see §4/§10.
- **Task 0 and Task 1** (the product-usage / referral / Track-screen
  observation tasks) are human tasks that cannot be fabricated by an AI
  assistant and were not attempted — they need the actual Simplify Money
  app, real friends, and real screenshots from you.
- **Five-minute walkthrough video** — not recorded (needs your voice/screen).
- The 4821 "145 vs 146" situation (§8) is not unfinished — it's the
  intended, investigated, documented outcome — but is listed here too so it
  isn't missed on a skim.

## 16. Known limitations

- TRANSFER matching uses a bounded time window (30 minutes) as a search
  bound, validated against corpus-a to produce zero false positives even at
  that width — but it is still a bound, and a hidden corpus with two
  genuinely unrelated same-amount opposite-leg transactions more than 30
  minutes apart across the user's own accounts would (correctly, per the
  evidence available) not be linked; conversely one occurring within the
  window with no real transfer relationship is not something the corpus
  gave any way to rule out. Documented rather than silently assumed away.
- Cross-channel (SMS/email) linking has no hard time cutoff by design
  (§14), so it depends on the merchant-normalization key holding across
  channels; a hidden corpus where the same bank spells a merchant
  differently between its SMS and email channels would not link them.
- Reconciliation's balance-chain method depends on messages actually
  quoting a balance; an account/channel that never does (like the
  credit-card account here) is explicitly reported as not reconcilable
  rather than guessed at.
