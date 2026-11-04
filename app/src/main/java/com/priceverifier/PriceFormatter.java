package com.priceverifier;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

final class PriceFormatter {
    private PriceFormatter() {
    }

    static String format(long cents) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"));
        format.setCurrency(Currency.getInstance("PHP"));
        return format.format(cents / 100.0);
    }
}
