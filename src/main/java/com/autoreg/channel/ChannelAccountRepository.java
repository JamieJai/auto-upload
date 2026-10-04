package com.autoreg.channel;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelAccountRepository extends JpaRepository<ChannelAccount, Long> {

    List<ChannelAccount> findByTenantIdOrderByChannelAsc(Long tenantId);

    Optional<ChannelAccount> findByIdAndTenantId(Long id, Long tenantId);

    Optional<ChannelAccount> findByTenantIdAndChannel(Long tenantId, Channel channel);

    boolean existsByTenantIdAndChannel(Long tenantId, Channel channel);
}
