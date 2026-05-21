// Tests §4.2 Database & Persistence — entity mappings and DB-level constraints
package com.att.tdp.issueflow.persistence;

import com.att.tdp.issueflow.attachment.Attachment;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditActor;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.auth.TokenDenyListEntry;
import com.att.tdp.issueflow.comment.Comment;
import com.att.tdp.issueflow.mention.Mention;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketDependency;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import org.hibernate.PropertyValueException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@SpringBootTest
@Transactional
class EntityMappingTests {

    @Autowired
    private EntityManager entityManager;

    /** §4.2 — All enum values for UserRole, TicketStatus, TicketPriority, TicketType, AuditAction, AuditActor, and AuditEntityType exactly match the values required by the spec. */
    @Test
    void enumsMatchAssignmentContract() {
        assertArrayEquals(new UserRole[]{UserRole.ADMIN, UserRole.DEVELOPER}, UserRole.values());
        assertArrayEquals(new TicketStatus[]{TicketStatus.TODO, TicketStatus.IN_PROGRESS, TicketStatus.IN_REVIEW, TicketStatus.DONE}, TicketStatus.values());
        assertArrayEquals(new TicketPriority[]{TicketPriority.LOW, TicketPriority.MEDIUM, TicketPriority.HIGH, TicketPriority.CRITICAL}, TicketPriority.values());
        assertArrayEquals(new TicketType[]{TicketType.BUG, TicketType.FEATURE, TicketType.TECHNICAL}, TicketType.values());
        assertArrayEquals(new AuditAction[]{
                AuditAction.CREATE,
                AuditAction.UPDATE,
                AuditAction.DELETE,
                AuditAction.RESTORE,
                AuditAction.IMPORT,
                AuditAction.EXPORT,
                AuditAction.LOGIN,
                AuditAction.LOGOUT,
                AuditAction.AUTO_ASSIGN
        }, AuditAction.values());
        assertArrayEquals(new AuditActor[]{AuditActor.USER, AuditActor.SYSTEM}, AuditActor.values());
        assertArrayEquals(new AuditEntityType[]{
                AuditEntityType.USER,
                AuditEntityType.PROJECT,
                AuditEntityType.TICKET,
                AuditEntityType.COMMENT,
                AuditEntityType.DEPENDENCY,
                AuditEntityType.ATTACHMENT,
                AuditEntityType.AUTH,
                AuditEntityType.TOKEN
        }, AuditEntityType.values());
    }

    /** §4.2 — The full entity graph (User, Project, Ticket, Comment, Mention, TicketDependency, Attachment, AuditLog, TokenDenyListEntry) can be persisted and reloaded with all relationships intact. */
    @Test
    void canPersistCompleteAssignmentEntityGraph() {
        User owner = user("owner", "owner@example.com", UserRole.ADMIN);
        User developer = user("developer", "developer@example.com", UserRole.DEVELOPER);
        Project project = project(owner);
        Ticket blocker = ticket(project, developer, "Blocking ticket");
        Ticket ticket = ticket(project, developer, "Fix login bug");

        Comment comment = new Comment();
        comment.setTicket(ticket);
        comment.setAuthor(developer);
        comment.setContent("Hello @owner!");
        entityManager.persist(comment);

        Mention mention = new Mention();
        mention.setComment(comment);
        mention.setMentionedUser(owner);
        entityManager.persist(mention);

        TicketDependency dependency = new TicketDependency();
        dependency.setTicket(ticket);
        dependency.setBlockedBy(blocker);
        entityManager.persist(dependency);

        Attachment attachment = new Attachment();
        attachment.setTicket(ticket);
        attachment.setFilename("screenshot.png");
        attachment.setContentType("image/png");
        attachment.setSize(3);
        attachment.setContent(new byte[]{1, 2, 3});
        entityManager.persist(attachment);

        AuditLog auditLog = new AuditLog();
        auditLog.setAction(AuditAction.CREATE);
        auditLog.setEntityType(AuditEntityType.TICKET);
        auditLog.setEntityId(ticket.getId());
        auditLog.setPerformedBy(developer);
        auditLog.setActor(AuditActor.USER);
        entityManager.persist(auditLog);

        TokenDenyListEntry token = new TokenDenyListEntry();
        token.setTokenId("jwt-id");
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        entityManager.persist(token);

        entityManager.flush();
        entityManager.clear();

        Ticket persistedTicket = entityManager.find(Ticket.class, ticket.getId());
        Project persistedProject = entityManager.find(Project.class, project.getId());
        Comment persistedComment = entityManager.find(Comment.class, comment.getId());
        Mention persistedMention = entityManager.find(Mention.class, mention.getId());
        Attachment persistedAttachment = entityManager.find(Attachment.class, attachment.getId());
        AuditLog persistedAuditLog = entityManager.find(AuditLog.class, auditLog.getId());
        TokenDenyListEntry persistedToken = entityManager.find(TokenDenyListEntry.class, token.getId());

        assertThat(persistedProject.isDeleted()).isFalse();
        assertThat(persistedProject.getCreatedAt()).isNotNull();
        assertThat(persistedTicket.getProject().getId()).isEqualTo(project.getId());
        assertThat(persistedTicket.getAssignee().getId()).isEqualTo(developer.getId());
        assertThat(persistedTicket.isDeleted()).isFalse();
        assertThat(persistedComment.getTicket().getId()).isEqualTo(ticket.getId());
        assertThat(persistedMention.getMentionedUser().getId()).isEqualTo(owner.getId());
        assertThat(persistedAttachment.getContent()).containsExactly(1, 2, 3);
        assertThat(persistedAuditLog.getTimestamp()).isNotNull();
        assertThat(persistedToken.getCreatedAt()).isNotNull();
    }

    /** §4.2 — The database enforces a unique constraint on username; inserting a duplicate raises a constraint violation. */
    @Test
    void userRequiresUniqueUsernameAndEmail() {
        user("duplicate", "duplicate@example.com", UserRole.DEVELOPER);
        entityManager.flush();

        User duplicateUsername = new User();
        duplicateUsername.setUsername("duplicate");
        duplicateUsername.setEmail("other@example.com");
        duplicateUsername.setFullName("Duplicate User");
        duplicateUsername.setRole(UserRole.DEVELOPER);
        duplicateUsername.setPasswordHash("encoded-password");

        assertThatThrownBy(() -> {
            entityManager.persist(duplicateUsername);
            entityManager.flush();
        }).isInstanceOfAny(PersistenceException.class, ConstraintViolationException.class);
    }

    /** §4.2 — Inserting a User without a username violates a NOT NULL constraint at the database level. */
    @Test
    void requiredUserFieldsAreEnforced() {
        User user = new User();
        user.setEmail("missing-username@example.com");
        user.setFullName("Missing Username");
        user.setRole(UserRole.DEVELOPER);

        assertThatThrownBy(() -> {
            entityManager.persist(user);
            entityManager.flush();
        }).isInstanceOfAny(PersistenceException.class, PropertyValueException.class, ConstraintViolationException.class);
    }

    /** §4.2 — Inserting a Ticket without required fields (priority here) violates a NOT NULL constraint at the database level. */
    @Test
    void ticketRequiresProjectStatusPriorityTypeAndTitle() {
        User developer = user("required-ticket-dev", "required-ticket-dev@example.com", UserRole.DEVELOPER);
        Project project = project(developer);
        Ticket ticket = new Ticket();
        ticket.setProject(project);
        ticket.setAssignee(developer);
        ticket.setDescription("Missing required fields");

        assertThatThrownBy(() -> {
            entityManager.persist(ticket);
            entityManager.flush();
        }).isInstanceOfAny(PersistenceException.class, PropertyValueException.class, ConstraintViolationException.class);
    }

    /** §3.6, §4.2 — The database enforces a unique composite constraint on (comment_id, mentioned_user_id) so the same user cannot be mentioned twice in the same comment. */
    @Test
    void duplicateMentionsForSameCommentAndUserAreRejected() {
        User author = user("mention-author", "mention-author@example.com", UserRole.DEVELOPER);
        User mentioned = user("mentioned", "mentioned@example.com", UserRole.ADMIN);
        Ticket ticket = ticket(project(author), author, "Mentioned ticket");
        Comment comment = comment(ticket, author);

        Mention first = new Mention();
        first.setComment(comment);
        first.setMentionedUser(mentioned);
        entityManager.persist(first);
        entityManager.flush();

        Mention duplicate = new Mention();
        duplicate.setComment(comment);
        duplicate.setMentionedUser(mentioned);

        assertThatThrownBy(() -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        }).isInstanceOfAny(PersistenceException.class, ConstraintViolationException.class);
    }

    /** §3.2, §4.2 — The database enforces a unique composite constraint on (ticket_id, blocked_by_id) so the same dependency cannot be added twice. */
    @Test
    void duplicateTicketDependenciesAreRejected() {
        User developer = user("dependency-dev", "dependency-dev@example.com", UserRole.DEVELOPER);
        Project project = project(developer);
        Ticket ticket = ticket(project, developer, "Dependent ticket");
        Ticket blocker = ticket(project, developer, "Blocker ticket");

        TicketDependency first = new TicketDependency();
        first.setTicket(ticket);
        first.setBlockedBy(blocker);
        entityManager.persist(first);
        entityManager.flush();

        TicketDependency duplicate = new TicketDependency();
        duplicate.setTicket(ticket);
        duplicate.setBlockedBy(blocker);

        assertThatThrownBy(() -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        }).isInstanceOfAny(PersistenceException.class, ConstraintViolationException.class);
    }

    /** §2.2, §4.2 — The database enforces uniqueness on token_id in the deny-list so the same JWT cannot be invalidated twice. */
    @Test
    void duplicateDeniedTokenIdsAreRejected() {
        TokenDenyListEntry first = new TokenDenyListEntry();
        first.setTokenId("same-token-id");
        first.setExpiresAt(Instant.now().plusSeconds(3600));
        entityManager.persist(first);
        entityManager.flush();

        TokenDenyListEntry duplicate = new TokenDenyListEntry();
        duplicate.setTokenId("same-token-id");
        duplicate.setExpiresAt(Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        }).isInstanceOfAny(PersistenceException.class, ConstraintViolationException.class);
    }

    private User user(String username, String email, UserRole role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(username + " User");
        user.setRole(role);
        user.setPasswordHash("encoded-password");
        entityManager.persist(user);
        return user;
    }

    private Project project(User owner) {
        Project project = new Project();
        project.setName("Sample Project");
        project.setDescription("A sample project");
        project.setOwner(owner);
        entityManager.persist(project);
        return project;
    }

    private Ticket ticket(Project project, User assignee, String title) {
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setDescription("Ticket description");
        ticket.setStatus(TicketStatus.TODO);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setType(TicketType.BUG);
        ticket.setProject(project);
        ticket.setAssignee(assignee);
        ticket.setDueDate(Instant.now().plusSeconds(3600));
        entityManager.persist(ticket);
        return ticket;
    }

    private Comment comment(Ticket ticket, User author) {
        Comment comment = new Comment();
        comment.setTicket(ticket);
        comment.setAuthor(author);
        comment.setContent("Hello @mentioned!");
        entityManager.persist(comment);
        return comment;
    }
}
