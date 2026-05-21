package com.att.tdp.issueflow.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Seeds a bootstrap admin user on startup when the users table is empty so the API is usable
// without a manual SQL insert. All endpoints except /auth/login require authentication, so
// without this, a fresh database cannot create its first user.
@Component
class UserBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${issueflow.bootstrap.admin-username:admin}")
    private String adminUsername;

    @Value("${issueflow.bootstrap.admin-password:admin}")
    private String adminPassword;

    @Value("${issueflow.bootstrap.admin-email:admin@issueflow.local}")
    private String adminEmail;

    UserBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setEmail(adminEmail);
        admin.setFullName("Bootstrap Admin");
        admin.setRole(UserRole.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        userRepository.save(admin);
        log.info("Seeded bootstrap admin '{}' (change the password via BOOTSTRAP_ADMIN_PASSWORD).", adminUsername);
    }
}
