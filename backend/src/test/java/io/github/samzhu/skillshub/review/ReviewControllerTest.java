package io.github.samzhu.skillshub.review;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.review.domain.Review;
import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.UserDisplay;
import io.github.samzhu.skillshub.shared.security.UserDisplayService;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S192-T03 — Review response display companion fields.
 */
@WebMvcTest(controllers = ReviewController.class)
class ReviewControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;

    @MockitoBean
    private CurrentUserProvider users;

    @MockitoBean
    private UserDisplayService userDisplayService;

    @Test
    @DisplayName("AC-S192-5: review row returns author display data while delete still uses authorId")
    @Tag("AC-S192-5")
    void reviewRowIncludesDisplayFieldsAndDeleteUsesAuthorId() throws Exception {
        var review = mockReview();
        when(reviewService.getReviewsBySkill("skill-1")).thenReturn(List.of(review));
        when(userDisplayService.resolveAll(eq(List.of("u_f7eb3a")), eq(false)))
                .thenReturn(Map.of("u_f7eb3a",
                        new UserDisplay("u_f7eb3a", "Sam Zhu", "samzhu", null)));

        mockMvc.perform(get("/api/v1/skills/skill-1/reviews").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authorId").value("u_f7eb3a"))
                .andExpect(jsonPath("$[0].authorDisplayName").value("Sam Zhu"))
                .andExpect(jsonPath("$[0].authorHandle").value("samzhu"));

        when(users.current()).thenReturn(
                CurrentUser.synthetic("u_f7eb3a", List.of("user"), List.of(), null));

        mockMvc.perform(delete("/api/v1/skills/skill-1/reviews/review-1").with(jwt()))
                .andExpect(status().isNoContent());

        verify(reviewService).deleteReview("review-1", "u_f7eb3a");
    }

    private static Review mockReview() {
        var review = mock(Review.class);
        when(review.getId()).thenReturn("review-1");
        when(review.getSkillId()).thenReturn("skill-1");
        when(review.getAuthorId()).thenReturn("u_f7eb3a");
        when(review.getRating()).thenReturn(5);
        when(review.getContent()).thenReturn("很好用");
        when(review.getCreatedAt()).thenReturn(Instant.parse("2026-05-17T08:00:00Z"));
        when(review.getUpdatedAt()).thenReturn(Instant.parse("2026-05-17T08:00:00Z"));
        return review;
    }
}
