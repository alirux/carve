package com.demo.billing;

import java.util.function.Function;

// Implementa Function<String,String>: CHA lo aggancia a QUALSIASI .apply() del modello,
// compreso quello di orders/billing con cui non ha alcun rapporto.
public class InvoiceFormatter implements Function<String, String> {
    @Override
    public String apply(String s) {
        return s.trim().toUpperCase();
    }
}
