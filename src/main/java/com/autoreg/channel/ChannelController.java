package com.autoreg.channel;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.autoreg.channel.ChannelDtos.CategoryMappingRequest;
import com.autoreg.channel.ChannelDtos.CategoryMappingResponse;
import com.autoreg.channel.ChannelDtos.ChannelAccountRequest;
import com.autoreg.channel.ChannelDtos.ChannelAccountResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tenants/{tenantId}")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelAccountService service;
    private final com.autoreg.channel.adapter.SmartStoreAdapter smartStore;

    @GetMapping("/channel-accounts")
    public List<ChannelAccountResponse> accounts(@PathVariable Long tenantId) {
        return service.list(tenantId).stream().map(ChannelAccountResponse::of).toList();
    }

    @PostMapping("/channel-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelAccountResponse createAccount(@PathVariable Long tenantId, @Valid @RequestBody ChannelAccountRequest req) {
        return ChannelAccountResponse.of(service.create(tenantId, req));
    }

    @PutMapping("/channel-accounts/{id}")
    public ChannelAccountResponse updateAccount(@PathVariable Long tenantId, @PathVariable Long id,
            @Valid @RequestBody ChannelAccountRequest req) {
        return ChannelAccountResponse.of(service.update(tenantId, id, req));
    }

    @PostMapping("/channel-accounts/{id}/template")
    public ChannelAccountResponse importTemplate(@PathVariable Long tenantId, @PathVariable Long id,
            @Valid @RequestBody ChannelDtos.TemplateRequest req) {
        try {
            return ChannelAccountResponse.of(service.importTemplate(tenantId, id, req.originProductNo(), smartStore));
        } catch (com.autoreg.channel.adapter.ChannelException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    @PostMapping("/category-mappings/{id}/reference")
    public CategoryMappingResponse importReference(@PathVariable Long tenantId, @PathVariable Long id,
            @Valid @RequestBody ChannelDtos.ReferenceRequest req) {
        try {
            return CategoryMappingResponse.of(service.importReference(tenantId, id, req.originProductNo(), Boolean.TRUE.equals(req.force()), smartStore));
        } catch (com.autoreg.channel.adapter.ChannelException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    @GetMapping("/category-mappings")
    public List<CategoryMappingResponse> mappings(@PathVariable Long tenantId) {
        return service.mappings(tenantId).stream().map(CategoryMappingResponse::of).toList();
    }

    @PutMapping("/category-mappings")
    public CategoryMappingResponse putMapping(@PathVariable Long tenantId, @Valid @RequestBody CategoryMappingRequest req) {
        return CategoryMappingResponse.of(service.putMapping(tenantId, req));
    }

    @DeleteMapping("/category-mappings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMapping(@PathVariable Long tenantId, @PathVariable Long id) {
        service.deleteMapping(tenantId, id);
    }
}
