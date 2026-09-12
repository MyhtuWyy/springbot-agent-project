package com.claw.dto;

public class InvoiceExportResponse {
    private String excelFileName;
    private String excelPath;
    private InvoiceRecognitionResult result;
    private InvoiceLedgerRow ledgerRow;

    public String getExcelFileName() {
        return excelFileName;
    }

    public void setExcelFileName(String excelFileName) {
        this.excelFileName = excelFileName;
    }

    public String getExcelPath() {
        return excelPath;
    }

    public void setExcelPath(String excelPath) {
        this.excelPath = excelPath;
    }

    public InvoiceRecognitionResult getResult() {
        return result;
    }

    public void setResult(InvoiceRecognitionResult result) {
        this.result = result;
    }

    public InvoiceLedgerRow getLedgerRow() {
        return ledgerRow;
    }

    public void setLedgerRow(InvoiceLedgerRow ledgerRow) {
        this.ledgerRow = ledgerRow;
    }
}
