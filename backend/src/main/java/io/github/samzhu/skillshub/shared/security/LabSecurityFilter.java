package io.github.samzhu.skillshub.shared.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * LAB 模式專用 — 把預設 lab user 注入 {@link SecurityContextHolder}，
 * 讓 {@code @PreAuthorize} / {@code @AuthenticationPrincipal} / {@link CurrentUserProvider}
 * 都能在無 JWT 情境下正確運作（S012）。
 *
 * <p>注入的 token 為 {@link UsernamePasswordAuthenticationToken}（已認證構造子，
 * {@code isAuthenticated() == true}），principal = {@code lab.user-id}、authorities =
 * {@code [ROLE_admin]}。LAB 測試者因此能完整驗證 admin 路徑（{@code /api/v1/admin/echo} 200 OK）。
 *
 * <p>插入位置：{@code SecurityConfig} 透過 {@code addFilterBefore} 將本 filter 放在
 * {@code UsernamePasswordAuthenticationFilter} 之前，確保 method security 與 authorization
 * decision 看到的是 lab user 而非 anonymous。
 *
 * <p>{@code finally} 區塊呼叫 {@link SecurityContextHolder#clearContext()} 為 explicit 意圖標示——
 * Spring Security 的 {@code FilterChainProxy} 本就會在 chain 結束時清 context，本 filter 額外清一次
 * 是為了「不依賴外層、自我封閉」原則，與框架行為不衝突。
 *
 * @see SkillshubProperties.Lab
 * @see CurrentUserProvider
 */
public class LabSecurityFilter extends OncePerRequestFilter {

    /** 從 {@code skillshub.security.lab.user-id} 帶入；注入為 token 的 principal 與 name。 */
    private final String labUserId;

    public LabSecurityFilter(String labUserId) {
        this.labUserId = labUserId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 用「已認證 token」三參構造子（third arg = authorities），
        // 確保 isAuthenticated() == true，@PreAuthorize / authenticated() 通過。
        var auth = new UsernamePasswordAuthenticationToken(
                labUserId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_admin")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            // 雙保險清理：FilterChainProxy 也會清，但這裡明示 lab user 不殘留至下一請求
            SecurityContextHolder.clearContext();
        }
    }
}
