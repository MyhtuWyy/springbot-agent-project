package com.claw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import com.claw.config.AppProperties;
import com.claw.config.AliOcrProperties;
import com.claw.config.ExcelMcpProperties;
import com.claw.config.McpProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties({AppProperties.class, McpProperties.class, ExcelMcpProperties.class, AliOcrProperties.class})
public class WeChatBotApplication {

    private static final Logger log = LoggerFactory.getLogger(WeChatBotApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(WeChatBotApplication.class, args);
        log.info("WeChatBot 应用启动完成");
    }
}
