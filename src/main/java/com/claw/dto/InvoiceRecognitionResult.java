package com.claw.dto;

import java.util.ArrayList;
import java.util.List;

public class InvoiceRecognitionResult {
    private boolean success;
    private String sourceFileName;
    private String status;
    private String errorCode;
    private String errorMessage;
    private String ocrRequestId;
    private String rawOcrResponse;
    private InvoiceFieldData invoice = new InvoiceFieldData();
    private InvoiceVerifyResult verify = new InvoiceVerifyResult();
    private List<String> warnings = new ArrayList<>();
    private List<String> missingVerifyFields = new ArrayList<>();

    public static InvoiceRecognitionResult failed(String status, String errorCode, String errorMessage, String fileName) {
        InvoiceRecognitionResult result = new InvoiceRecognitionResult();
        result.setSuccess(false);
        result.setStatus(status);
        result.setErrorCode(errorCode);
        result.setErrorMessage(errorMessage);
        result.setSourceFileName(fileName);
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getOcrRequestId() {
        return ocrRequestId;
    }

    public void setOcrRequestId(String ocrRequestId) {
        this.ocrRequestId = ocrRequestId;
    }

    public String getRawOcrResponse() {
        return rawOcrResponse;
    }

    public void setRawOcrResponse(String rawOcrResponse) {
        this.rawOcrResponse = rawOcrResponse;
    }

    public InvoiceFieldData getInvoice() {
        return invoice;
    }

    public void setInvoice(InvoiceFieldData invoice) {
        this.invoice = invoice;
    }

    public InvoiceVerifyResult getVerify() {
        return verify;
    }

    public void setVerify(InvoiceVerifyResult verify) {
        this.verify = verify;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public List<String> getMissingVerifyFields() {
        return missingVerifyFields;
    }

    public void setMissingVerifyFields(List<String> missingVerifyFields) {
        this.missingVerifyFields = missingVerifyFields;
    }
}
