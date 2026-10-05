package com.autoreg.product;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 판매자 범위 밖의 상품은 없는 것으로 취급한다 */
    Optional<Product> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Product> findByTenantIdAndCode(Long tenantId, String code);

    boolean existsByTenantIdAndCode(Long tenantId, String code);

    @org.springframework.data.jpa.repository.Query("select p.code from Product p where p.tenantId = :tenantId and p.code like concat(:prefix, '%')")
    java.util.List<String> findCodesStartingWith(Long tenantId, String prefix);

    Page<Product> findByTenantId(Long tenantId, Pageable pageable);

    Page<Product> findByTenantIdAndStatus(Long tenantId, ProductStatus status, Pageable pageable);

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    long countByStatus(ProductStatus status);

    long countByTenantIdAndStatus(Long tenantId, ProductStatus status);
}
