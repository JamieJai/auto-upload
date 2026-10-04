package com.autoreg.channel;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelListingRepository extends JpaRepository<ChannelListing, Long> {

    List<ChannelListing> findByProductId(Long productId);

    Optional<ChannelListing> findByProductIdAndChannelAccountId(Long productId, Long channelAccountId);
}
