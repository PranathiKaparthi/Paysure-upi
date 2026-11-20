package com.paysure.identity.application;

import com.paysure.common.error.BadRequestException;
import com.paysure.common.error.ConflictException;
import com.paysure.common.error.NotFoundException;
import com.paysure.common.error.UnauthorizedException;
import com.paysure.identity.domain.Role;
import com.paysure.identity.domain.User;
import com.paysure.identity.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** BCrypt ignores input beyond 72 bytes; reject rather than silently truncate. */
    static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    /** Used to spend comparable time when the e-mail is unknown, so timing does not reveal registered users. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-" + UUID.randomUUID());
    }

    @Transactional
    public User register(String email, String rawPassword, String fullName) {
        return createUser(email, rawPassword, fullName, Role.USER);
    }

    /** Used by the demo seeder and tests. The public register endpoint can only ever create USER accounts. */
    @Transactional
    public User createUser(String email, String rawPassword, String fullName, Role role) {
        String normalized = User.normalizeEmail(email);
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BadRequestException("PASSWORD_TOO_LONG", "Password must be at most 72 bytes.");
        }
        if (users.existsByEmail(normalized)) {
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "An account with this e-mail already exists.");
        }
        try {
            User user = users.saveAndFlush(new User(normalized, passwordEncoder.encode(rawPassword), fullName, role));
            log.info("Registered user {} with role {}", user.getId(), role);
            return user;
        } catch (DataIntegrityViolationException race) {
            // Two concurrent registrations for the same e-mail: the unique constraint decides.
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "An account with this e-mail already exists.");
        }
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String rawPassword) {
        Optional<User> found = users.findByEmail(User.normalizeEmail(email));
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            throw invalidCredentials();
        }
        User user = found.get();
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash()) || !user.isEnabled()) {
            throw invalidCredentials();
        }
        return new LoginResult(user, tokenService.issue(user));
    }

    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found."));
    }

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("INVALID_CREDENTIALS", "Invalid e-mail or password.");
    }

    public record LoginResult(User user, TokenService.IssuedToken token) {
    }
}
