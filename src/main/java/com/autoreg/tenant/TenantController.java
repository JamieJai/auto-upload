package com.autoreg.tenant;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.autoreg.tenant.TenantDtos.TenantRequest;
import com.autoreg.tenant.TenantDtos.TenantResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService service;

    @GetMapping
    public List<TenantResponse> list() {
        return service.list().stream().map(TenantResponse::of).toList();
    }

    @GetMapping("/{id}")
    public TenantResponse get(@PathVariable Long id) {
        return TenantResponse.of(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(@Valid @RequestBody TenantRequest req) {
        return TenantResponse.of(service.create(req));
    }

    @PutMapping("/{id}")
    public TenantResponse update(@PathVariable Long id, @Valid @RequestBody TenantRequest req) {
        return TenantResponse.of(service.update(id, req));
    }
}
