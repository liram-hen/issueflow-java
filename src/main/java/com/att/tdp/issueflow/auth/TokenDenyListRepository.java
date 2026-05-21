package com.att.tdp.issueflow.auth;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenDenyListRepository extends JpaRepository<TokenDenyListEntry, Long> {

    boolean existsByTokenIdAndExpiresAtAfter(String tokenId, Instant now);

    void deleteAllByExpiresAtBefore(Instant threshold);
}
