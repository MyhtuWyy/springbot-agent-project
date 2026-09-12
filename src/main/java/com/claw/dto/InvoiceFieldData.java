package com.claw.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class InvoiceFieldData {
    private String invoiceCode;
    private String invoiceNo;
    private String invoiceDate;
    private String invoiceSum;
    private String invoiceTypeCode;
    private Integer invoiceKind;
    private String verifyCode;
    private String buyerName;
    private String sellerName;
    private String invoiceTitle;
    private String amountWithoutTax;
    private String taxAmount;
    private Map<String, String> extractedFields = new LinkedHashMap<>();

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

    public String getInvoiceTypeCode() {
        return invoiceTypeCode;
    }

    public void setInvoiceTypeCode(String invoiceTypeCode) {
        this.invoiceTypeCode = invoiceTypeCode;
    }

    public Integer getInvoiceKind() {
        return invoiceKind;
    }

    public void setInvoiceKind(Integer invoiceKind) {
        this.invoiceKind = invoiceKind;
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

    public Map<String, String> getExtractedFields() {
        return extractedFields;
    }

    public void setExtractedFields(Map<String, String> extractedFields) {
        this.extractedFields = extractedFields;
    }
}
