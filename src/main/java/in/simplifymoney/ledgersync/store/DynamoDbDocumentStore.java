package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.identity.TransactionIdentity;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DynamoDbDocumentStore implements DocumentStore, AutoCloseable {

    public record QueryResult(
            List<NormalizedTxn> transactions,
            int examined,
            int returned) {
    }

    private static final String DEFAULT_TABLE = "ledger-sync";

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoDbDocumentStore(DynamoDbClient client) {
        this(client, DEFAULT_TABLE);
    }

    public DynamoDbDocumentStore(
            DynamoDbClient client,
            String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    @Override
    public void save(NormalizedTxn txn) {
        String id = TransactionIdentity.of(txn).toString();

        String pk = "ACCT#" + txn.accountLast4();
        String sk = sortKey(txn, id);

        Map<String, AttributeValue> item = new HashMap<>();

        item.put("PK", s(pk));
        item.put("SK", s(sk));
        item.put("txn_id", s(id));
        item.put("account_last4", s(txn.accountLast4()));
        item.put("occurred_at", s(txn.occurredAt().toString()));
        item.put("direction", s(txn.direction().name()));
        item.put("amount", n(txn.amount()));
        item.put("category", s(txn.category().name()));
        item.put("merchant", s(txn.merchant()));
        item.put("source_message_ids", ss(txn.sourceMessageIds()));

        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression(
                            "attribute_not_exists(PK) AND attribute_not_exists(SK)")
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            return;
        }

        writeMessageIndexes(txn, id, pk, sk);
        addCategoryTotal(txn);
    }

    private void writeMessageIndexes(
            NormalizedTxn txn,
            String txnId,
            String transactionPk,
            String transactionSk) {

        for (String messageId : txn.sourceMessageIds()) {

            Map<String, AttributeValue> indexItem = new HashMap<>();

            indexItem.put(
                    "PK",
                    s("MSG_INDEX#" + messageId));

            indexItem.put(
                    "SK",
                    s("TXN#" + txnId));

            indexItem.put(
                    "GSI2PK",
                    s("MSG#" + messageId));

            indexItem.put(
                    "GSI2SK",
                    s("TXN"));

            indexItem.put(
                    "txn_id",
                    s(txnId));

            indexItem.put(
                    "transaction_pk",
                    s(transactionPk));

            indexItem.put(
                    "transaction_sk",
                    s(transactionSk));

            try {
                client.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(indexItem)
                        .conditionExpression(
                                "attribute_not_exists(PK) AND attribute_not_exists(SK)")
                        .build());
            } catch (ConditionalCheckFailedException ignored) {
                // Idempotent replay.
            }
        }
    }

    private void addCategoryTotal(NormalizedTxn txn) {

        String categoryPk =
                "TOTAL#"
                        + txn.accountLast4()
                        + "#"
                        + txn.category().name();

        Map<String, AttributeValue> keys = Map.of(
                "PK", s(categoryPk),
                "SK", s("TOTAL"));

        Map<String, AttributeValue> values =
                Map.of(":amount", n(txn.amount()));

        client.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(keys)
                .updateExpression("ADD #total :amount")
                .expressionAttributeNames(
                        Map.of("#total", "total"))
                .expressionAttributeValues(values)
                .build());

        Map<String, AttributeValue> indexFields =
                Map.of(
                        "GSI1PK",
                        s("ACCT#"
                                + txn.accountLast4()
                                + "#CAT#"
                                + txn.category().name()),

                        "GSI1SK",
                        s("TOTAL"),

                        "category",
                        s(txn.category().name()),

                        "account_last4",
                        s(txn.accountLast4()));

        client.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(keys)
                .updateExpression(
                        "SET GSI1PK = :gpk, "
                                + "GSI1SK = :gsk, "
                                + "category = :category, "
                                + "account_last4 = :account")
                .expressionAttributeValues(
                        Map.of(
                                ":gpk",
                                indexFields.get("GSI1PK"),

                                ":gsk",
                                indexFields.get("GSI1SK"),

                                ":category",
                                indexFields.get("category"),

                                ":account",
                                indexFields.get("account_last4")))
                .build());
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4,
            YearMonth month) {

        return queryAccountMonth(
                accountLast4,
                month).transactions();
    }

    public QueryResult queryAccountMonth(
            String accountLast4,
            YearMonth month) {

        String prefix = "TXN#" + month;

        QueryResponse response = client.query(
                QueryRequest.builder()
                        .tableName(tableName)
                        .keyConditionExpression(
                                "PK = :pk AND begins_with(SK, :prefix)")
                        .expressionAttributeValues(
                                Map.of(
                                        ":pk",
                                        s("ACCT#" + accountLast4),

                                        ":prefix",
                                        s(prefix)))
                        .scanIndexForward(false)
                        .build());

        List<NormalizedTxn> transactions =
                response.items()
                        .stream()
                        .filter(item ->
                                item.containsKey("txn_id"))
                        .map(this::fromItem)
                        .sorted(
                                Comparator
                                        .comparing(
                                                NormalizedTxn::occurredAt)
                                        .reversed())
                        .toList();

        return new QueryResult(
                transactions,
                response.scannedCount(),
                response.count());
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(
            String accountLast4) {

        Map<Category, BigDecimal> totals =
                new HashMap<>();

        for (Category category : Category.values()) {

            QueryResponse response =
                    client.query(
                            QueryRequest.builder()
                                    .tableName(tableName)
                                    .indexName("CategoryIndex")
                                    .keyConditionExpression(
                                            "GSI1PK = :pk "
                                                    + "AND GSI1SK = :sk")
                                    .expressionAttributeValues(
                                            Map.of(
                                                    ":pk",
                                                    s("ACCT#"
                                                            + accountLast4
                                                            + "#CAT#"
                                                            + category.name()),

                                                    ":sk",
                                                    s("TOTAL")))
                                    .limit(1)
                                    .build());

            if (!response.items().isEmpty()) {

                AttributeValue value =
                        response.items()
                                .get(0)
                                .get("total");

                if (value != null
                        && value.n() != null) {

                    totals.put(
                            category,
                            new BigDecimal(value.n())
                                    .setScale(2));
                }
            }
        }

        for (Category category :
                Category.values()) {

            totals.putIfAbsent(
                    category,
                    BigDecimal.ZERO.setScale(2));
        }

        return totals;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(
            String messageId) {

        QueryResponse response =
                client.query(
                        QueryRequest.builder()
                                .tableName(tableName)
                                .indexName("MessageIndex")
                                .keyConditionExpression(
                                        "GSI2PK = :pk")
                                .expressionAttributeValues(
                                        Map.of(
                                                ":pk",
                                                s("MSG#" + messageId)))
                                .limit(1)
                                .build());

        if (response.items().isEmpty()) {
            return Optional.empty();
        }

        Map<String, AttributeValue> indexItem =
                response.items().get(0);

        String transactionPk =
                indexItem
                        .get("transaction_pk")
                        .s();

        String transactionSk =
                indexItem
                        .get("transaction_sk")
                        .s();

        Map<String, AttributeValue> transactionItem =
                client.getItem(
                        GetItemRequest.builder()
                                .tableName(tableName)
                                .key(
                                        Map.of(
                                                "PK",
                                                s(transactionPk),

                                                "SK",
                                                s(transactionSk)))
                                .consistentRead(true)
                                .build())
                        .item();

        if (transactionItem == null
                || transactionItem.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                fromItem(transactionItem));
    }

    public List<NormalizedTxn> all() {

        List<NormalizedTxn> result =
                new ArrayList<>();

        Map<String, AttributeValue> lastKey = null;

        do {

            var requestBuilder =
                    software.amazon.awssdk.services.dynamodb.model.ScanRequest
                            .builder()
                            .tableName(tableName);

            if (lastKey != null
                    && !lastKey.isEmpty()) {

                requestBuilder
                        .exclusiveStartKey(lastKey);
            }

            var response =
                    client.scan(
                            requestBuilder.build());

            for (Map<String, AttributeValue> item :
                    response.items()) {

                if (item.containsKey("txn_id")
                        && item.containsKey("occurred_at")
                        && item.containsKey("amount")) {

                    result.add(fromItem(item));
                }
            }

            lastKey =
                    response.lastEvaluatedKey();

        } while (lastKey != null
                && !lastKey.isEmpty());

        return result;
    }

    private static String sortKey(
            NormalizedTxn txn,
            String id) {

        return "TXN#"
                + YearMonth.from(txn.occurredAt())
                + "#"
                + txn.occurredAt()
                + "#"
                + Integer.toUnsignedString(
                        id.hashCode());
    }

    private NormalizedTxn fromItem(
            Map<String, AttributeValue> item) {

        List<String> messageIds =
                item.get("source_message_ids") == null
                        ? List.of()
                        : item.get("source_message_ids").ss();

        return new NormalizedTxn(
                item.get("account_last4").s(),

                OffsetDateTime.parse(
                        item.get("occurred_at").s()),

                Direction.valueOf(
                        item.get("direction").s()),

                new BigDecimal(
                        item.get("amount").n())
                        .setScale(2),

                Category.valueOf(
                        item.get("category").s()),

                item.get("merchant").s(),

                messageIds);
    }

    private static AttributeValue s(
            String value) {

        return AttributeValue.builder()
                .s(value)
                .build();
    }

    private static AttributeValue n(
            BigDecimal value) {

        return AttributeValue.builder()
                .n(value.setScale(2).toPlainString())
                .build();
    }

    private static AttributeValue ss(
            List<String> values) {

        return AttributeValue.builder()
                .ss(new HashSet<>(values))
                .build();
    }

    @Override
    public void close() {
        client.close();
    }
}