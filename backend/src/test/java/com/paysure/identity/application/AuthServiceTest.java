package com.paysure.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paysure.common.error.BadRequestException;
import com.paysure.common.error.ConflictException;
import com.paysure.common.error.UnauthorizedException;
import com.paysure.identity.domain.Role;
import com.paysure.identity.domain.User;
import com.paysure.identity.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests with Mockito: no Spring context, no database. */
class AuthServiceTest {

    private UserRepository users;
    private PasswordEncoder encoder;
    private TokenService tokens;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        tokens = mock(TokenService.class);
        when(encoder.encode(anyString())).thenReturn("$2a$hash");
        service = new AuthService(users, encoder, tokens);
    }

    @Test
    void unknownEmailStillPerformsAPasswordComparison() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("Ghost@Example.com", "whatever1"))
                .isInstanceOf(UnauthorizedException.class);
        // Constant-work defence against user enumeration via response timing.
        verify(encoder, times(1)).matches(eq("whatever1"), anyString());
        verify(tokens, never()).issue(any());
    }

    @Test
    void wrongPasswordIsRejectedWithoutIssuingAToken() {
        User user = new User("a@example.com", "$2a$real", "A", Role.USER);
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("bad", "$2a$real")).thenReturn(false);

        assertThatThrownBy(() -> service.login("a@example.com", "bad")).isInstanceOf(UnauthorizedException.class);
        verify(tokens, never()).issue(any());
    }

    @Test
    void successfulLoginIssuesToken() {
        User user = new User("a@example.com", "$2a$real", "A", Role.USER);
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("good1234", "$2a$real")).thenReturn(true);
        when(tokens.issue(user)).thenReturn(new TokenService.IssuedToken("jwt", 900));

        assertThat(service.login("a@example.com", "good1234").token().value()).isEqualTo("jwt");
    }

    @Test
    void registrationRaceOnUniqueEmailBecomesConflict() {
        when(users.existsByEmail("race@example.com")).thenReturn(false);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> service.register("race@example.com", "Passw0rd!", "Race"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void passwordsLongerThanBcryptLimitAreRejectedNotTruncated() {
        String longPassword = "ä".repeat(40) + "1"; // 81 bytes in UTF-8
        assertThatThrownBy(() -> service.register("long@example.com", longPassword, "Long"))
                .isInstanceOf(BadRequestException.class);
        verify(users, never()).saveAndFlush(any());
    }
}
