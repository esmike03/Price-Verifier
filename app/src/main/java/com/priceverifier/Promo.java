package com.priceverifier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A promo or special price for one barcode. "Promo" and "special price" behave
 * the same; the label only changes the banner text and colour.
 *
 * <p>{@link #value} depends on the type: hundredths of a percent for
 * {@link Type#PERCENT} (5000 = 50%), centavos for {@link Type#AMOUNT} and
 * {@link Type#FIXED}, unused for {@link Type#BUY_TAKE}. Dates are inclusive
 * {@code yyyy-MM-dd} strings in device-local time; empty means open-ended.
 */
final class Promo {
    enum Type { PERCENT, AMOUNT, FIXED, BUY_TAKE }

    static final String LABEL_PROMO = "PROMO";
    static final String LABEL_SPECIAL = "SPECIAL";
    static final String SOURCE_SERVER = "server";
    static final String SOURCE_DEVICE = "device";
    /** " · " and " – ", built from code points so the source stays ASCII-safe. */
    static final String DOT = " " + (char) 0x00B7 + " ";
    static final String DASH = " " + (char) 0x2013 + " ";

    final long id;
    final String barcode;
    final String source;
    final String label;
    final Type type;
    final long value;
    final int buyQty;
    final int takeQty;
    final String startDate;
    final String endDate;

    Promo(long id, String barcode, String source, String label, Type type, long value,
          int buyQty, int takeQty, String startDate, String endDate) {
        this.id = id;
        this.barcode = Product.normalizeBarcode(barcode);
        this.source = source;
        this.label = LABEL_SPECIAL.equals(label) ? LABEL_SPECIAL : LABEL_PROMO;
        this.type = type;
        this.value = value;
        this.buyQty = buyQty;
        this.takeQty = takeQty;
        this.startDate = startDate == null ? "" : startDate;
        this.endDate = endDate == null ? "" : endDate;
    }

    boolean isServer() {
        return SOURCE_SERVER.equals(source);
    }

    boolean isSpecial() {
        return LABEL_SPECIAL.equals(label);
    }

    /** {@code today} is {@code yyyy-MM-dd}, which compares correctly as a string. */
    boolean isActiveOn(String today) {
        return (startDate.isEmpty() || startDate.compareTo(today) <= 0)
                && (endDate.isEmpty() || today.compareTo(endDate) <= 0);
    }

    boolean reducesPrice() {
        return type != Type.BUY_TAKE;
    }

    /** The price after this promo; only meaningful when {@link #reducesPrice()}. */
    long promoPriceCents(long regularCents) {
        switch (type) {
            case PERCENT:
                long off = BigDecimal.valueOf(regularCents).multiply(BigDecimal.valueOf(value))
                        .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValue();
                return Math.max(0, regularCents - off);
            case AMOUNT:
                return Math.max(0, regularCents - value);
            case FIXED:
                return value;
            default:
                return regularCents;
        }
    }

    String labelText() {
        return isSpecial() ? "SPECIAL PRICE" : "PROMO";
    }

    /** Short deal text for the banner, e.g. "50% OFF", "₱20.00 OFF", "BUY 1 TAKE 1". */
    String dealText(long regularCents) {
        switch (type) {
            case PERCENT:
                return formatPercent(value) + "% OFF";
            case AMOUNT:
                return PriceFormatter.format(value) + " OFF";
            case FIXED:
                long saved = regularCents - value;
                return saved > 0 ? "SAVE " + PriceFormatter.format(saved) : "";
            default:
                return "BUY " + buyQty + " TAKE " + takeQty;
        }
    }

    /** One-line summary for admin lists, e.g. "PROMO · 50% OFF · Oct 1 – Oct 31, 2026". */
    String summary(long regularCents) {
        StringBuilder sb = new StringBuilder(labelText());
        String deal = type == Type.FIXED ? PriceFormatter.format(value) : dealText(regularCents);
        if (!deal.isEmpty()) {
            sb.append(DOT).append(deal);
        }
        String dates = dateRangeText();
        if (!dates.isEmpty()) {
            sb.append(DOT).append(dates);
        }
        return sb.toString();
    }

    String dateRangeText() {
        if (startDate.isEmpty() && endDate.isEmpty()) {
            return "";
        }
        if (startDate.isEmpty()) {
            return "until " + displayDate(endDate);
        }
        if (endDate.isEmpty()) {
            return "from " + displayDate(startDate);
        }
        return displayDate(startDate) + DASH + displayDate(endDate);
    }

    /** The promo shown on the price check: the lowest price wins; Buy X Take Y rides along. */
    static final class Best {
        final Promo priced;
        final long priceCents;
        final Promo buyTake;

        Best(Promo priced, long priceCents, Promo buyTake) {
            this.priced = priced;
            this.priceCents = priceCents;
            this.buyTake = buyTake;
        }

        boolean isEmpty() {
            return priced == null && buyTake == null;
        }
    }

    static Best choose(List<Promo> promos, long regularCents, String today) {
        Promo priced = null;
        long bestPrice = regularCents;
        Promo buyTake = null;
        for (Promo promo : promos) {
            if (!promo.isActiveOn(today)) {
                continue;
            }
            if (promo.reducesPrice()) {
                long price = promo.promoPriceCents(regularCents);
                // Only a real reduction counts; a "promo" at or above the shelf price is ignored.
                if (price < bestPrice) {
                    bestPrice = price;
                    priced = promo;
                }
            } else if (promo.buyQty > 0 && promo.takeQty > 0
                    && (buyTake == null || promo.takeQty * (long) buyTake.buyQty
                    > buyTake.takeQty * (long) promo.buyQty)) {
                buyTake = promo;
            }
        }
        return new Best(priced, bestPrice, buyTake);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static String displayDate(String isoDate) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate);
            return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date);
        } catch (ParseException | NullPointerException ex) {
            return isoDate;
        }
    }

    /**
     * Accepts yyyy-MM-dd and the M/d/yyyy form Excel tends to save; returns
     * yyyy-MM-dd, "" for blank, or null when the text is not a date.
     */
    static String parseDate(String text) {
        String cleaned = Product.clean(text);
        if (cleaned.isEmpty()) {
            return "";
        }
        for (String pattern : new String[]{"yyyy-MM-dd", "M/d/yyyy", "yyyy/M/d"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setLenient(false);
            try {
                Date date = format.parse(cleaned);
                if (date != null) {
                    return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date);
                }
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    static Type parseType(String text) {
        String t = Product.clean(text).toLowerCase(Locale.US).replace('_', ' ').replace('-', ' ');
        switch (t) {
            case "percent": case "percent off": case "%": case "pct": case "discount":
                return Type.PERCENT;
            case "amount": case "amount off": case "less": case "peso off":
                return Type.AMOUNT;
            case "fixed": case "fixed price": case "price": case "promo price": case "special price":
                return Type.FIXED;
            case "buy take": case "buy x take y": case "bxty": case "b1t1": case "buy1take1":
                return Type.BUY_TAKE;
            default:
                return null;
        }
    }

    static String typeKey(Type type) {
        switch (type) {
            case PERCENT: return "percent";
            case AMOUNT: return "amount";
            case FIXED: return "fixed";
            default: return "buy_take";
        }
    }

    static String parseLabel(String text) {
        String t = Product.clean(text).toLowerCase(Locale.US);
        return t.startsWith("special") || t.equals("sp") ? LABEL_SPECIAL : LABEL_PROMO;
    }

    /** "50" or "12.5" percent to hundredths of a percent; null when invalid. */
    static Long parsePercent(String text) {
        Long hundredths = ProductImporter.parsePriceCents(Product.clean(text).replace("%", ""));
        return hundredths == null || hundredths <= 0 || hundredths > 10000 ? null : hundredths;
    }

    static String formatPercent(long hundredths) {
        return BigDecimal.valueOf(hundredths, 2).stripTrailingZeros().toPlainString();
    }

    static String formatAmount(long cents) {
        return String.format(Locale.US, "%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    /** The value as typed in a form or CSV cell: "50" for 50%, "20.00" for ₱20. */
    String valueText() {
        switch (type) {
            case PERCENT: return formatPercent(value);
            case AMOUNT: case FIXED: return formatAmount(value);
            default: return "";
        }
    }
}
