package com.demo.orders;

import java.util.function.Function;

public class LabelRenderer {
    private Function<String, String> decorator;

    public String render(String label) {
        // Nessun riferimento a billing, ma CHA collegherà questo .apply()
        // a com.demo.billing.InvoiceFormatter: edge fantasma cross-progetto.
        return decorator.apply(label);
    }
}
