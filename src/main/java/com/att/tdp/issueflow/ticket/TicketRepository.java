package com.att.tdp.issueflow.ticket;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findAllByProjectIdAndDeletedFalse(Long projectId);

    List<Ticket> findAllByProjectIdAndDeletedTrue(Long projectId);

    Optional<Ticket> findByIdAndDeletedFalse(Long id);

    long countByAssigneeIdAndProjectIdAndDeletedFalseAndStatusNot(Long assigneeId, Long projectId, TicketStatus status);

    List<Ticket> findAllByDeletedFalseAndStatusNotAndDueDateBeforeAndEscalatedAtIsNull(TicketStatus status, java.time.Instant dueDate);

    @Query("""
            select new com.att.tdp.issueflow.ticket.WorkloadRow(
                u.id,
                u.username,
                count(t.id)
            )
            from User u
            left join Ticket t
                on t.assignee = u
                and t.project.id = :projectId
                and t.deleted = false
                and t.status <> com.att.tdp.issueflow.ticket.TicketStatus.DONE
            where u.role = com.att.tdp.issueflow.user.UserRole.DEVELOPER
            group by u.id, u.username
            order by count(t.id) asc, u.id asc
            """)
    List<WorkloadRow> findProjectWorkload(@Param("projectId") Long projectId);
}
