package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.comment.CommentResponse;
import com.att.tdp.issueflow.comment.MentionedUserResponse;
import com.att.tdp.issueflow.common.DuplicateResourceException;
import com.att.tdp.issueflow.common.ResourceNotFoundException;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import com.att.tdp.issueflow.mention.Mention;
import com.att.tdp.issueflow.mention.MentionPageResponse;
import com.att.tdp.issueflow.mention.MentionRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final MentionRepository mentionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    public UserService(
            UserRepository userRepository,
            MentionRepository mentionRepository,
            PasswordEncoder passwordEncoder,
            AuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.mentionRepository = mentionRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(Long userId) {
        return UserResponse.from(findUser(userId));
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username already exists");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already exists");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setRole(request.role());
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        User saved = userRepository.save(user);
        auditLogService.log(AuditAction.CREATE, AuditEntityType.USER, saved.getId());
        return UserResponse.from(saved);
    }

    @Transactional
    public void updateUser(Long userId, UpdateUserRequest request) {
        User user = findUser(userId);
        if (StringUtils.hasText(request.fullName())) {
            user.setFullName(request.fullName());
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        auditLogService.log(AuditAction.UPDATE, AuditEntityType.USER, user.getId());
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = findUser(userId);
        userRepository.delete(user);
        auditLogService.log(AuditAction.DELETE, AuditEntityType.USER, userId);
    }

    @Transactional(readOnly = true)
    public MentionPageResponse getMentionsForUser(Long userId, int page, int pageSize) {
        findUser(userId);
        Page<Mention> mentionPage = mentionRepository.findAllByMentionedUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page - 1, pageSize));
        List<CommentResponse> data = mentionPage.getContent().stream()
                .map(mention -> {
                    var comment = mention.getComment();
                    List<MentionedUserResponse> mentionedUsers = mentionRepository
                            .findAllByCommentId(comment.getId())
                            .stream()
                            .map(m -> MentionedUserResponse.from(m.getMentionedUser()))
                            .toList();
                    return new CommentResponse(
                            comment.getId(),
                            comment.getTicket().getId(),
                            comment.getAuthor().getId(),
                            comment.getContent(),
                            mentionedUsers
                    );
                })
                .toList();
        return new MentionPageResponse(data, mentionPage.getTotalElements(), page);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }


}
