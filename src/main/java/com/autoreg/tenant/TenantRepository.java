package com.autoreg.tenant;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    List<Tenant> findAllByOrderByNameAsc();

    boolean existsByCode(String code);
}
