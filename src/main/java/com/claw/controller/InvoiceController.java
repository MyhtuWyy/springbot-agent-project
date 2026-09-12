package com.claw.controller;

import com.claw.dto.InvoiceLedgerRow;
import com.claw.dto.InvoiceExportResponse;
import com.claw.dto.InvoiceRecognitionRequest;
import com.claw.dto.InvoiceRecognitionResult;
import com.claw.service.InvoiceRecognitionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {
    private final InvoiceRecognitionService invoiceRecognitionService;

    public InvoiceController(InvoiceRecognitionService invoiceRecognitionService) {
        this.invoiceRecognitionService = invoiceRecognitionService;
    }

    @PostMapping(value = "/recognize-verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InvoiceRecognitionResult recognizeAndVerify(@RequestPart("file") MultipartFile file,
                                                       @RequestPart(value = "request", required = false) InvoiceRecognitionRequest request) {
        return invoiceRecognitionService.recognizeAndVerify(file, request);
    }

    @PostMapping(value = "/recognize-verify/ledger-row", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> recognizeAndBuildLedgerRow(@RequestPart("file") MultipartFile file,
                                                          @RequestPart(value = "request", required = false) InvoiceRecognitionRequest request) {
        InvoiceRecognitionResult result = invoiceRecognitionService.recognizeAndVerify(file, request);
        return Map.of(
                "result", result,
                "ledgerRow", InvoiceLedgerRow.from(result)
        );
    }

    @PostMapping(value = "/export-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InvoiceExportResponse recognizeAndExportExcel(@RequestPart("file") MultipartFile file,
                                                         @RequestPart(value = "request", required = false) InvoiceRecognitionRequest request) {
        return invoiceRecognitionService.recognizeVerifyAndExport(file, request);
    }
}
