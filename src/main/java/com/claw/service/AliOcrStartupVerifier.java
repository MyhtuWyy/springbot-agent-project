package com.claw.service;

import com.claw.config.AliOcrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AliOcrStartupVerifier implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AliOcrStartupVerifier.class);

    private final AliOcrProperties properties;

    public AliOcrStartupVerifier(AliOcrProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean sdkPresent = isClassPresent("com.aliyun.ocr_api20210707.Client");
        log.info("Aliyun OCR startup check: enabled={}, endpoint={}, sdkPresent={}, autoVerify={}, accessKeyIdMasked={}, accessKeySecretConfigured={}, defaultTicketKeys={}",
                properties.isEnabled(),
                properties.getEndpoint(),
                sdkPresent,
                properties.isAutoVerify(),
                maskAccessKeyId(properties.getAccessKeyId()),
                !isBlank(properties.getAccessKeySecret()),
                properties.getDefaultTicketKeys());
        if (properties.isEnabled() && !sdkPresent) {
            log.error("Aliyun OCR SDK missing at runtime. Dependency com.aliyun:ocr_api20210707 is not available.");
        }
        if (properties.isEnabled()
                && (isBlank(properties.getAccessKeyId()) || isBlank(properties.getAccessKeySecret()))) {
            log.error("Aliyun OCR is enabled but AccessKey configuration is incomplete.");
        }
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String maskAccessKeyId(String value) {
        if (isBlank(value)) {
            return "(empty)";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 6) {
            return trimmed.charAt(0) + "***";
        }
        return trimmed.substring(0, 4) + "***" + trimmed.substring(trimmed.length() - 2);
    }
}
