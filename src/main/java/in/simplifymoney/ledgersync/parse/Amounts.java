package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 *
 * ==========================================================================
 * INCIDENT NOTE (INC-2026-09-11): the amount pattern used to require exactly
 * two decimal digits ("\.[0-9]{2}"). A whole-rupee figure with no paise -
 * "Rs.5", "Rs 8,000", "INR 90" - simply did not match it. Since first(String)
 * scanned the WHOLE message body for the first match, a message like
 * "Rs.5 debited ... Avl Bal: Rs.92,213.10" would skip straight past the real
 * amount (no match) and match the balance instead, reporting a spend of
 * Rs.92,213.10 for what was actually a Rs.5 transaction.
 *
 * The fix has two parts, both needed:
 *   1. The regex now makes the fractional part optional, so whole-rupee
 *      figures match at all: "([0-9,]+(?:\.[0-9]{1,2})?)".
 *   2. More importantly, the four supported parsers (HdfcSmsParser,
 *      IciciSmsParser, EmailParser) no longer call first(String) against the
 *      raw body at all. Each extracts the amount as a named group WITHIN its
 *      own per-format transaction-clause regex, so there is no longer a
 *      "scan the whole message for the first rupee figure" step to get
 *      confused by a balance or limit mentioned later in the same body. This
 *      is the general fix: it holds regardless of what other rupee figures
 *      (balance, limit, previous due) appear in the message, and regardless
 *      of whether the amount happens to have paise or not.
 *
 * first(String) is kept only as a narrow fallback utility (and to keep
 * existing callers/tests meaningful); it is not on the primary parsing path
 * for any of the four implemented formats any more.
 * ==========================================================================
 */
public final class Amounts {

    private Amounts() {}

    private static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");

    /**
     * Deliberately does NOT match "Avl Limit" - a credit-card available
     * limit is not an account balance and is not reconcilable the same way
     * (see HdfcSmsParser's CARD handling and Reports.reconciliation).
     */
    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    /**
     * The first rupee figure in the message. Kept as a narrow fallback for
     * formats with no dedicated parser; NOT used by HdfcSmsParser,
     * IciciSmsParser or EmailParser (see the incident note above).
     */
    public static BigDecimal first(String body) {
        Matcher m = AMOUNT.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** Parses a rupee figure already isolated by a caller (e.g. a regex group). */
    public static BigDecimal parse(String raw) {
        return toDecimal(raw);
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
