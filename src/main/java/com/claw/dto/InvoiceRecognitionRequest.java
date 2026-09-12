package com.claw.dto;

import java.util.ArrayList;
import java.util.List;

public class InvoiceRecognitionRequest {
    private Boolean verifyEnabled;
    private List<String> ticketKeys = new ArrayList<>();
    private InvoiceVerifyOverrides overrides = new InvoiceVerifyOverrides();

    public Boolean getVerifyEnabled() {
        return verifyEnabled;
    }

    public void setVerifyEnabled(Boolean verifyEnabled) {
        this.verifyEnabled = verifyEnabled;
    }

    public List<String> getTicketKeys() {
        return ticketKeys;
    }

    public void setTicketKeys(List<String> ticketKeys) {
        this.ticketKeys = ticketKeys;
    }

    public InvoiceVerifyOverrides getOverrides() {
        return overrides;
    }

    public void setOverrides(InvoiceVerifyOverrides overrides) {
        this.overrides = overrides;
    }
}
