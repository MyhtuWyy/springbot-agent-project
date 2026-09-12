package com.claw.service;

public class AliOcrInvoiceException extends RuntimeException {
    private final AliOcrInvoiceErrorType errorType;

    public AliOcrInvoiceException(AliOcrInvoiceErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public AliOcrInvoiceException(AliOcrInvoiceErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public AliOcrInvoiceErrorType getErrorType() {
        return errorType;
    }
}
