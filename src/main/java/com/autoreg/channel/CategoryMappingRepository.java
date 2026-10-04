package com.autoreg.channel;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryMappingRepository extends JpaRepository<CategoryMapping, Long> {

    List<CategoryMapping> findByTenantIdOrderByChannelAscCategoryAsc(Long tenantId);

    Optional<CategoryMapping> findByTenantIdAndChannelAndCategory(Long tenantId, Channel channel, String category);

    Optional<CategoryMapping> findByIdAndTenantId(Long id, Long tenantId);
}
