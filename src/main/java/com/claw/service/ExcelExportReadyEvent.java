package com.claw.service;

import java.nio.file.Path;

public record ExcelExportReadyEvent(String exportId,
                                    String userId,
                                    String sessionId,
                                    Path filePath,
                                    String fileName,
                                    String messageText,
                                    String caption) {
}
