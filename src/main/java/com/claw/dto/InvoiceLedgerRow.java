package com.claw.dto;

public class InvoiceLedgerRow {
    private String sourceFileName;
    private String invoiceCode;
    private String invoiceNo;
    private String invoiceDate;
    private String invoiceSum;
    private String verifyCode;
    private String buyerName;
    private String sellerName;
    private String invoiceTitle;
    private String amountWithoutTax;
    private String taxAmount;
    private String verifyStatus;
    private String verifyMessage;
    private String invoiceVerdict;

    public static InvoiceLedgerRow from(InvoiceRecognitionResult result) {
        InvoiceLedgerRow row = new InvoiceLedgerRow();
        if (result == null) {
            return row;
        }
        InvoiceFieldData invoice = result.getInvoice();
        InvoiceVerifyResult verify = result.getVerify();
        row.setSourceFileName(result.getSourceFileName());
        if (invoice != null) {
            row.setInvoiceCode(invoice.getInvoiceCode());
            row.setInvoiceNo(invoice.getInvoiceNo());
            row.setInvoiceDate(invoice.getInvoiceDate());
            row.setInvoiceSum(invoice.getInvoiceSum());
            row.setVerifyCode(invoice.getVerifyCode());
            row.setBuyerName(invoice.getBuyerName());
            row.setSellerName(invoice.getSellerName());
            row.setInvoiceTitle(invoice.getInvoiceTitle());
            row.setAmountWithoutTax(invoice.getAmountWithoutTax());
            row.setTaxAmount(invoice.getTaxAmount());
        }
        if (verify != null) {
            row.setVerifyStatus(verify.getStatus());
            row.setVerifyMessage(verify.getMessage());
        }
        row.setInvoiceVerdict(resolveVerdict(result));
        return row;
    }

    public static String resolveVerdict(InvoiceRecognitionResult result) {
        if (result == null || result.getVerify() == null || !result.getVerify().isAttempted()) {
            return "\u5173\u952e\u5b57\u6bb5\u4e0d\u8db3\u65e0\u6cd5\u6838\u9a8c\u771f\u4f2a";
        }
        return result.getVerify().isPassed()
                ? "\u771f\u5b9e\u6709\u6548\u53d1\u7968"
                : "\u53d1\u7968\u4fe1\u606f\u5f02\u5e38\u7591\u4f3c\u5047\u7968";
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getInvoiceCode() {
        return invoiceCode;
    }

    public void setInvoiceCode(String invoiceCode) {
        this.invoiceCode = invoiceCode;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public void setInvoiceNo(String invoiceNo) {
        this.invoiceNo = invoiceNo;
    }

    public String getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(String invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public String getInvoiceSum() {
        return invoiceSum;
    }

    public void setInvoiceSum(String invoiceSum) {
        this.invoiceSum = invoiceSum;
    }

    public String getVerifyCode() {
        return verifyCode;
    }

    public void setVerifyCode(String verifyCode) {
        this.verifyCode = verifyCode;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public void setBuyerName(String buyerName) {
        this.buyerName = buyerName;
    }

    public String getSellerName() {
        return sellerName;
    }

    public void setSellerName(String sellerName) {
        this.sellerName = sellerName;
    }

    public String getInvoiceTitle() {
        return invoiceTitle;
    }

    public void setInvoiceTitle(String invoiceTitle) {
        this.invoiceTitle = invoiceTitle;
    }

    public String getAmountWithoutTax() {
        return amountWithoutTax;
    }

    public void setAmountWithoutTax(String amountWithoutTax) {
        this.amountWithoutTax = amountWithoutTax;
    }

    public String getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(String taxAmount) {
        this.taxAmount = taxAmount;
    }

    public String getVerifyStatus() {
        return verifyStatus;
    }

    public void setVerifyStatus(String verifyStatus) {
        this.verifyStatus = verifyStatus;
    }

    public String getVerifyMessage() {
        return verifyMessage;
    }

    public void setVerifyMessage(String verifyMessage) {
        this.verifyMessage = verifyMessage;
    }

    public String getInvoiceVerdict() {
        return invoiceVerdict;
    }

    public void setInvoiceVerdict(String invoiceVerdict) {
        this.invoiceVerdict = invoiceVerdict;
    }
}
