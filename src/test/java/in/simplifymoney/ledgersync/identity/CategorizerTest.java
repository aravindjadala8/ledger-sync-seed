package in.simplifymoney.ledgersync.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategorizerTest {

    private final Categorizer categorizer = new Categorizer();

    private TxnGroup group(String acct, Direction dir, String amount, String merchant, String at) {
        return new TxnGroup(acct, dir, new BigDecimal(amount), merchant,
                OffsetDateTime.parse(at), true, "m-" + acct + amount + at);
    }

    @Test
    void aUpiDebitOf100OrLessIsMicro() {
        List<TxnGroup> gs = new ArrayList<>(List.of(
                group("4821", Direction.DEBIT, "100.00", "UPI/VEGETABLE VENDOR",
                        "2026-07-01T10:00:00+05:30")));
        categorizer.categorize(gs);
        assertEquals(Category.MICRO, gs.get(0).category());
    }

    @Test
    void aUpiDebitOverTheThresholdIsSpendNotMicro() {
        List<TxnGroup> gs = new ArrayList<>(List.of(
                group("4821", Direction.DEBIT, "100.01", "UPI/VEGETABLE VENDOR",
                        "2026-07-01T10:00:00+05:30")));
        categorizer.categorize(gs);
        assertEquals(Category.SPEND, gs.get(0).category());
    }

    @Test
    void aNonUpiDebitOf100OrLessIsSpendNotMicro() {
        // corpus-a evidence: IRCTC 99.99, BIGBASKET 99.99, SWIGGY 47.33 etc.
        // are all <=100 but not UPI-prefixed, and stay SPEND - amount alone
        // is not the rule.
        List<TxnGroup> gs = new ArrayList<>(List.of(
                group("4821", Direction.DEBIT, "99.99", "IRCTC", "2026-07-01T10:00:00+05:30")));
        categorizer.categorize(gs);
        assertEquals(Category.SPEND, gs.get(0).category());
    }

    @Test
    void aMatchedOppositeLegBetweenTheUsersAccountsIsATransfer() {
        List<TxnGroup> gs = new ArrayList<>(List.of(
                group("4821", Direction.DEBIT, "8000.00", "IMPS/P2A/PARAG KAPOOR",
                        "2026-07-05T11:00:00+05:30"),
                group("9075", Direction.CREDIT, "8000.00", "IMPS/P2A/PARAG KAPOOR",
                        "2026-07-05T11:02:00+05:30")));
        categorizer.categorize(gs);
        assertEquals(Category.TRANSFER, gs.get(0).category());
        assertEquals(Category.TRANSFER, gs.get(1).category());
    }

    @Test
    void aDebitWithNoMatchingOppositeLegStaysSpendEvenWithATransferLikeMerchantName() {
        // corpus-a's IMPS/P2A/RAHUL SHARMA: same merchant SHAPE as a real
        // transfer, but no opposite leg exists anywhere - must remain SPEND.
        List<TxnGroup> gs = new ArrayList<>(List.of(
                group("4821", Direction.DEBIT, "12000.00", "IMPS/P2A/RAHUL SHARMA",
                        "2026-07-10T10:00:00+05:30")));
        categorizer.categorize(gs);
        assertEquals(Category.SPEND, gs.get(0).category());
    }

    @Test
    void aSameAmountDebitAndCreditOnTheSameAccountIsNotATransfer() {
        // opposite direction + same amount is not enough on its own - it
        // must be a DIFFERENT account, or it's just an ordinary debit and
        // credit on the same account.
        List<TxnGroup> gs = new ArrayList<>(List.of(
                group("4821", Direction.DEBIT, "3000.00", "ATM WDL NOIDA",
                        "2026-07-15T20:05:00+05:30"),
                group("4821", Direction.CREDIT, "3000.00", "REVERSAL ATM WDL NOIDA",
                        "2026-07-16T11:30:00+05:30")));
        categorizer.categorize(gs);
        assertEquals(Category.SPEND, gs.get(0).category());
        assertEquals(Category.INCOME, gs.get(1).category());
    }

    @Test
    void aMicroSelfTransferIsCategorizedAsTransferNotMicro() {
        // TRANSFER must be checked before MICRO.
        List<TxnGroup> gs = new ArrayList<>(List.of(
                group("4821", Direction.DEBIT, "50.00", "UPI/OWN ACCOUNT",
                        "2026-07-05T11:00:00+05:30"),
                group("9075", Direction.CREDIT, "50.00", "UPI/OWN ACCOUNT",
                        "2026-07-05T11:01:00+05:30")));
        categorizer.categorize(gs);
        assertEquals(Category.TRANSFER, gs.get(0).category());
        assertEquals(Category.TRANSFER, gs.get(1).category());
    }
}
