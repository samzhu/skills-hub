package io.github.samzhu.skillshub.shared.api;

import java.io.IOException;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SPA fallback — 把使用者直接打的 SPA 深層連結（{@code /publish}、{@code /skills/sk-1} 等）
 * forward 到 {@code /index.html}，讓 React Router 接手 client-side 路由。
 *
 * <p>S152: 由 explicit allowlist 改用 catchall pattern；React 加新 route 不再需要同步 backend，
 * 外部 typo URL 也走進 React {@code NotFoundPage}（取代 Whitelabel / XML 錯誤頁）。
 *
 * <p>規則：
 * <ul>
 *   <li>無副檔名的 path（{@code [^.]*}）→ forward 到 {@code /index.html} → React Router 接手</li>
 *   <li>{@code /api/} 開頭一律 404（保留 JSON 4xx 給 API client）</li>
 *   <li>含副檔名（{@code /assets/index.js}、{@code /favicon.ico}）→ pattern 不匹配 →
 *       走 Spring static resource handler</li>
 * </ul>
 *
 * <p>HandlerMapping 順序保證 {@code @RequestMapping("/api/v1/...")} 會先吃掉 API 路徑，
 * catchall 接不到 controller-mapped API endpoint；只有 {@code /api/} 開頭但沒任何 controller
 * 匹配（例如 {@code /api/foo} 的 typo）才會落到本 controller，此時走 early-return 404。
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html#mvc-ann-requestmapping-uri-templates">Spring Path Patterns</a>
 */
@Controller
class SpaFallbackController {

    @GetMapping({"/{path:[^.]*}", "/**/{path:[^.]*}"})
    Object forwardToIndex(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        return "forward:/index.html";
    }
}
