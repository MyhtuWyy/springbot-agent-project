package com.claw.dto;

import java.util.ArrayList;
import java.util.List;

public class InvoiceBatchExportResponse {
    private String excelFileName;
    private String excelPath;
    private int totalCount;
    private int successCount;
    private int failCount;
    private List<InvoiceRecognitionResult> results = new ArrayList<>();

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

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public List<InvoiceRecognitionResult> getResults() {
        return results;
    }

    public void setResults(List<InvoiceRecognitionResult> results) {
        this.results = results;
    }
}
