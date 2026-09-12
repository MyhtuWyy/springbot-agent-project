package com.claw.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class InvoiceVerifyResult {
    private boolean attempted;
    private boolean passed;
    private String status;
    private String message;
    private String requestId;
    private String rawResponse;
    private Map<String, String> verifyDetails = new LinkedHashMap<>();

    public boolean isAttempted() {
        return attempted;
    }

    public void setAttempted(boolean attempted) {
        this.attempted = attempted;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public Map<String, String> getVerifyDetails() {
        return verifyDetails;
    }

    public void setVerifyDetails(Map<String, String> verifyDetails) {
        this.verifyDetails = verifyDetails;
    }
}
