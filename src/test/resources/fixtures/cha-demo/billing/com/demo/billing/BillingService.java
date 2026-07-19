package com.demo.billing;

import org.springframework.stereotype.Service;

@Service
public class BillingService {
    private InvoiceFormatter formatter;

    public String format(String raw) {
        return formatter.apply(raw);   // DIRETTO: classe concreta, stesso progetto
    }
}
