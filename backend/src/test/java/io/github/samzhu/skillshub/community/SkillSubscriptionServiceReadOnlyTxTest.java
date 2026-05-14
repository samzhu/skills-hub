package io.github.samzhu.skillshub.community;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.github.samzhu.skillshub.SkillshubProperties;
import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.JwtClaimAnomalyMetrics;
import io.github.samzhu.skillshub.shared.security.User;
import io.github.samzhu.skillshub.shared.security.UserRepository;
import io.github.samzhu.skillshub.shared.security.UserUpsertService;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

@Import({
        SkillSubscriptionService.class,
        CurrentUserProvider.class,
        UserUpsertService.class,
        SkillSubscriptionServiceReadOnlyTxTest.TestConfig.class
})
class SkillSubscriptionServiceReadOnlyTxTest extends RepositorySliceTestBase {

    private static final String SUBSCRIBER_ID = "u_5450fa";
    private static final String OAUTH_SUB = "oauth-sub-5450fa";

    @Autowired
    private SkillSubscriptionService service;

    @Autowired
    private SkillSubscriptionRepository subscriptionRepo;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private UserRepository userRepo;

    private String skillId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        subscriptionRepo.deleteAll();
        skillRepo.deleteAll();
        userRepo.deleteAll();

        var now = Instant.now();
        userRepo.save(User.createNew(SUBSCRIBER_ID, "google", OAUTH_SUB,
                "alice@example.com", "Alice Before", "alice", null, now));
        userRepo.save(User.createNew("u_author", "google", "author-sub",
                "author@example.com", "Author Name", "author", null, now));

        skillId = UUID.randomUUID().toString();
        skillRepo.save(Skill.fromRow(
                skillId, "subscribed-skill", "summary fixture", "u_author", "devops",
                "1.0.0", null, "PUBLISHED", 0L, now, now,
                List.of("public:*:read"), null));
        skillRepo.updateRiskLevel(skillId, "LOW", now);
        subscriptionRepo.save(SkillSubscription.create(skillId, SUBSCRIBER_ID));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Tag("AC-S173-3")
    @DisplayName("AC-S173-3: subscription details can refresh OAuth user inside read-only caller")
    void subscriptionDetailsCanRefreshOAuthUserInsideReadOnlyCaller() {
        authenticateAsExistingOAuthUser();

        var details = service.findSubscriptionDetailsOfCurrentUser();

        assertThat(details).singleElement().satisfies(summary -> {
            assertThat(summary.skillId()).isEqualTo(skillId);
            assertThat(summary.skillName()).isEqualTo("subscribed-skill");
            assertThat(summary.author()).isEqualTo("u_author");
            assertThat(summary.authorDisplayName()).isEqualTo("Author Name");
            assertThat(summary.latestVersion()).isEqualTo("1.0.0");
            assertThat(summary.riskLevel()).isEqualTo("LOW");
            assertThat(summary.status()).isEqualTo("PUBLISHED");
        });
        assertThat(userRepo.findById(SUBSCRIBER_ID).orElseThrow().getName())
                .isEqualTo("Alice After");
    }

    private static void authenticateAsExistingOAuthUser() {
        var jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(OAUTH_SUB)
                .claim("email", "alice.after@example.com")
                .claim("name", "Alice After")
                .claim("picture", "https://example.com/alice.png")
                .claims(claims -> claims.putAll(Map.of(
                        "roles", List.of("viewer"),
                        "groups", List.of())))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        JwtClaimAnomalyMetrics jwtClaimAnomalyMetrics(MeterRegistry registry) {
            return new JwtClaimAnomalyMetrics(registry);
        }

        @Bean
        SkillshubProperties skillshubProperties() {
            return new SkillshubProperties(
                    null,
                    null,
                    null,
                    null,
                    new SkillshubProperties.Security(
                            null,
                            new SkillshubProperties.Lab("lab-user"),
                            null,
                            null));
        }
    }
}
