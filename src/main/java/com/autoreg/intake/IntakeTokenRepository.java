package com.autoreg.intake;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeTokenRepository extends JpaRepository<IntakeToken, Long> {

    Optional<IntakeToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    List<IntakeToken> findAllByOrderByIdDesc();
}
