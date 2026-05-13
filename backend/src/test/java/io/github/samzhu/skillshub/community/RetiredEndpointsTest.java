package io.github.samzhu.skillshub.community;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S156c AC-2 regression — voting-board pivot 後拆掉的 3 個 endpoint 對外不可用。
 *
 * <p>RequestCommandController 已把 {@code @PostMapping("/{id}/claim")} /
 * {@code @DeleteMapping("/{id}/claim")} / {@code @PostMapping("/{id}/fulfill")}
 * 三個 method 整個拿掉 → Spring 啟動掃 controller 時 router 不會註冊這 3 條路徑。
 *
 * <p>實際行為：Spring Boot 對「沒對應 @RestController 的路徑」走 fallthrough 至
 * {@code ResourceHttpRequestHandler}（static resource handler）→ POST/DELETE 一律回
 * <b>405 Method Not Allowed</b>（resource handler 只支援 GET），而非 404。對 user 而言
 * 兩者等效（endpoint 都不可用）；本測試用 {@code is4xxClientError()} 涵蓋兩種情況，
 * 守住「未來如果不小心把這 3 條 endpoint 加回去」會被擋下（加回去後 status 會變 200/201/204）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RetiredEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST /api/v1/requests/{id}/claim → 4xx (endpoint 已隨 S156c 拆除；實際 405 per resource handler fallthrough)")
    void claim_endpoint_disabled() throws Exception {
        mockMvc.perform(post("/api/v1/requests/any-id/claim").with(user("alice")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: DELETE /api/v1/requests/{id}/claim → 4xx (release endpoint 已拆)")
    void release_endpoint_disabled() throws Exception {
        mockMvc.perform(delete("/api/v1/requests/any-id/claim").with(user("alice")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST /api/v1/requests/{id}/fulfill → 4xx (fulfill endpoint 已拆)")
    void fulfill_endpoint_disabled() throws Exception {
        mockMvc.perform(post("/api/v1/requests/any-id/fulfill")
                        .with(user("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skillId":"sk-1"}
                                """))
                .andExpect(status().is4xxClientError());
    }
}
