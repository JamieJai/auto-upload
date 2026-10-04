package com.autoreg.workflow;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autoreg.product.ProductDtos.ProductResponse;
import com.autoreg.product.TextField;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tenants/{tenantId}/products/{id}")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService service;

    public record RejectRequest(String note) {}

    public record GenerateRequest(@NotNull TextField field) {}

    @PostMapping("/submit")
    public ProductResponse submit(@PathVariable Long tenantId, @PathVariable Long id) {
        return service.submit(tenantId, id);
    }

    @PostMapping("/approve")
    public ProductResponse approve(@PathVariable Long tenantId, @PathVariable Long id) {
        return service.approve(tenantId, id);
    }

    @PostMapping("/reject")
    public ProductResponse reject(@PathVariable Long tenantId, @PathVariable Long id, @RequestBody(required = false) RejectRequest req) {
        return service.reject(tenantId, id, req == null ? null : req.note());
    }

    @PostMapping("/reopen")
    public ProductResponse reopen(@PathVariable Long tenantId, @PathVariable Long id) {
        return service.reopen(tenantId, id);
    }

    @PostMapping("/cancel")
    public ProductResponse cancel(@PathVariable Long tenantId, @PathVariable Long id) {
        return service.cancel(tenantId, id);
    }

    @PostMapping("/generate")
    public ProductResponse generate(@PathVariable Long tenantId, @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody GenerateRequest req) {
        return service.generateField(tenantId, id, req.field());
    }
}
