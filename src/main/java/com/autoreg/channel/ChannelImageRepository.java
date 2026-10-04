package com.autoreg.channel;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelImageRepository extends JpaRepository<ChannelImage, Long> {

    List<ChannelImage> findByChannelAccountIdAndProductImageIdIn(Long channelAccountId, Collection<Long> productImageIds);
}
