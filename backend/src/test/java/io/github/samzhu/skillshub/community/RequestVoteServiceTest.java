package io.github.samzhu.skillshub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.api.RequestNotFoundException;

/**
 * S096g2-T02 — RequestVoteService toggle 行為驗證（Testcontainers）。
 *
 * <p>涵蓋 S096g2 AC-5（toggle on）+ AC-6（toggle off 重複 POST）+ negative。
 *
 * <p><b>S156c AC-3 anchor</b>：voting-board pivot 拆 claim/release/fulfill machinery 後，
 * vote toggle 是僅存的 mutation flow；本檔的 {@code voteToggle_fullCycle_postPivot} 為
 * 對應「voting-board pivot 後 vote 仍 work」regression 驗證的單一集中點。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RequestVoteServiceTest {

    @Autowired
    private RequestVoteService voteService;

    @Autowired
    private RequestService requestService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM request_votes");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM domain_events");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: toggle on — 第一次 vote → voted=true, count+1")
    void toggleOn() {
        var id = requestService.createRequest("a", "x", "alice");

        var result = voteService.toggle(id, "bob");

        assertThat(result.voted()).isTrue();
        assertThat(result.voteCount()).isEqualTo(1L);
        var dbCount = jdbc.queryForObject("SELECT vote_count FROM requests WHERE id = ?", Long.class, id);
        assertThat(dbCount).isEqualTo(1L);
        var voteRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_votes WHERE request_id = ? AND user_id = ?",
                Long.class, id, "bob");
        assertThat(voteRow).isEqualTo(1L);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: toggle off — 同 user 第二次 vote → voted=false, count-1")
    void toggleOff() {
        var id = requestService.createRequest("a", "x", "alice");
        voteService.toggle(id, "bob"); // count = 1

        var result = voteService.toggle(id, "bob"); // count = 0

        assertThat(result.voted()).isFalse();
        assertThat(result.voteCount()).isZero();
        var voteRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_votes WHERE request_id = ?",
                Long.class, id);
        assertThat(voteRow).isZero();
    }

    @Test
    @DisplayName("不同 user 各自 toggle 互不影響")
    void multipleUsers() {
        var id = requestService.createRequest("a", "x", "alice");

        voteService.toggle(id, "bob");
        voteService.toggle(id, "carol");
        var afterDave = voteService.toggle(id, "dave");

        assertThat(afterDave.voted()).isTrue();
        assertThat(afterDave.voteCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("toggle on non-existent request → RequestNotFoundException")
    void toggle_notFound_throws() {
        assertThatThrownBy(() -> voteService.toggle("non-existent-uuid", "bob"))
                .isInstanceOf(RequestNotFoundException.class);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("S156c AC-3 regression: voting-board pivot 後 vote toggle 完整 on→off→on cycle 仍 work")
    void voteToggle_fullCycle_postPivot() {
        var id = requestService.createRequest("regression target", "x", "alice");

        // on
        var step1 = voteService.toggle(id, "bob");
        assertThat(step1.voted()).isTrue();
        assertThat(step1.voteCount()).isEqualTo(1L);

        // off
        var step2 = voteService.toggle(id, "bob");
        assertThat(step2.voted()).isFalse();
        assertThat(step2.voteCount()).isZero();

        // 第三次 toggle 再 on — 驗 PostgreSQL ON CONFLICT DO NOTHING + DELETE-then-INSERT
        // 來回切換不會卡死
        var step3 = voteService.toggle(id, "bob");
        assertThat(step3.voted()).isTrue();
        assertThat(step3.voteCount()).isEqualTo(1L);

        // 換另一個 user 同一 request — voteCount 累加 (S156c 拆 status column 後 row 仍正常 INSERT)
        var step4 = voteService.toggle(id, "carol");
        assertThat(step4.voted()).isTrue();
        assertThat(step4.voteCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("vote_count 不會出負數（GREATEST guard）")
    void voteCount_neverNegative() {
        var id = requestService.createRequest("a", "x", "alice");
        // 直接 SQL 試 vote_count -1（模擬 race 異常）— schema CHECK >= 0 應拒絕
        assertThatThrownBy(() ->
                jdbc.update("UPDATE requests SET vote_count = -1 WHERE id = ?", id)
        ).hasMessageContaining("vote_count_check"); // PostgreSQL CHECK constraint name
    }
}
