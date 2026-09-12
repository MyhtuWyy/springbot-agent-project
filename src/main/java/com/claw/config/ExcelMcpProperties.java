package com.claw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "excel.mcp")
public class ExcelMcpProperties {
    private boolean enabled;
    private String fileRoot = "D:/mcp-server-files/excel";
    private int previewRowLimit = 10;
    private int maxReadRows = 2000;
    private int maxExportRows = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPreviewRowLimit() {
        return previewRowLimit;
    }

    public void setPreviewRowLimit(int previewRowLimit) {
        this.previewRowLimit = previewRowLimit;
    }

    public String getFileRoot() {
        return fileRoot;
    }

    public void setFileRoot(String fileRoot) {
        this.fileRoot = fileRoot;
    }

    public int getMaxReadRows() {
        return maxReadRows;
    }

    public void setMaxReadRows(int maxReadRows) {
        this.maxReadRows = maxReadRows;
    }

    public int getMaxExportRows() {
        return maxExportRows;
    }

    public void setMaxExportRows(int maxExportRows) {
        this.maxExportRows = maxExportRows;
    }
}
