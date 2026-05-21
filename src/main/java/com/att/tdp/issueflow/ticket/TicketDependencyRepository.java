package com.att.tdp.issueflow.ticket;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketDependencyRepository extends JpaRepository<TicketDependency, Long> {

    List<TicketDependency> findAllByTicketId(Long ticketId);

    boolean existsByTicketIdAndBlockedById(Long ticketId, Long blockedById);

    Optional<TicketDependency> findByTicketIdAndBlockedById(Long ticketId, Long blockedById);

    void deleteAllByTicketId(Long ticketId);
}
