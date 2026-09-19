package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.store.DynamoDbDocumentStore;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.net.URI;
import java.time.YearMonth;
import java.util.Map;

public final class DynamoQueryCheck {

    public static void main(String[] args) {

        DynamoDbClient client = DynamoDbClient.builder()
                .endpointOverride(
                        URI.create("http://localhost:8000"))
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        "local",
                                        "local")))
                .build();

        try (DynamoDbDocumentStore store =
                     new DynamoDbDocumentStore(
                             client,
                             "ledger-sync")) {

            System.out.println("=== Q1: ACCOUNT + MONTH ===");

            var q1 = store.queryAccountMonth(
                    "4821",
                    YearMonth.of(2026, 6));

            System.out.println(
                    "examined=" + q1.examined()
                            + ", returned=" + q1.returned());

            q1.transactions().forEach(txn ->
                    System.out.println(
                            txn.occurredAt()
                                    + " | "
                                    + txn.amount()
                                    + " | "
                                    + txn.merchant()));

            System.out.println();

            System.out.println("=== Q2: CATEGORY TOTALS ===");

            Map<Category, java.math.BigDecimal> totals =
                    store.categoryTotals("4821");

            totals.forEach(
                    (category, total) ->
                            System.out.println(
                                    category + " = " + total));

            System.out.println();

            System.out.println("=== Q3: MESSAGE ID ===");

            String messageId = "m-legacy-0001";

            var result =
                    store.byMessageId(messageId);

            if (result.isPresent()) {
                var txn = result.get();

                System.out.println(
                        "message_id=" + messageId);

                System.out.println(
                        "transaction="
                                + txn.occurredAt()
                                + " | "
                                + txn.amount()
                                + " | "
                                + txn.merchant());

                System.out.println(
                        "source_message_ids="
                                + txn.sourceMessageIds());
            } else {
                System.out.println(
                        "No transaction found for "
                                + messageId);
            }
        }
    }
}