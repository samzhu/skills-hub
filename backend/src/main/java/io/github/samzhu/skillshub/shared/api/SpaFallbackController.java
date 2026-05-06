package io.github.samzhu.skillshub.shared.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback — 把使用者直接打的 SPA 深層連結（{@code /publish}、{@code /skills/sk-1} 等）
 * forward 到 {@code /index.html}，讓 React Router 接手 client-side 路由。
 *
 * <p>背景：前端 React 用 BrowserRouter，URL 結構由前端 Routes 配置（非 hash router）。
 * 使用者重整或書籤直接訪問非根路徑時，瀏覽器送 GET 到 backend；backend 沒對應 controller
 * → 預設回 404。本 controller 顯式列出所有「frontend SPA 擁有」的路徑前綴，
 * 一律 forward 到 {@code /index.html}（由 Spring static resource handler 服務 React build
 * 出的 index.html，掛 hydrate 後 React Router 看到 location.pathname 渲染對應 page）。
 *
 * <p>用 explicit list 而不是 catchall regex（如 {@code /**}/{path:[^.]*}）：避免誤攔
 * 不存在的 {@code /api/...} 端點 → 應回 404 JSON 給前端 / curl，不該回 HTML SPA shell。
 *
 * <p>列表來源：{@code frontend/src/App.tsx} 的 {@code <Route path="...">}。新增 React
 * 路由時記得對齊本 controller。
 *
 * @see <a href="https://reactrouter.com/start/declarative/installation">React Router declarative routing</a>
 */
@Controller
class SpaFallbackController {

    @GetMapping({
        "/browse",
        "/publish", "/publish/**",
        "/my-skills",
        "/collections",
        "/requests",
        "/notifications",
        "/analytics",
        "/flags",
        "/search",
        "/skills", "/skills/**",
        "/docs/**",
        "/auth-debug",
    })
    String forwardToIndex() {
        return "forward:/index.html";
    }
}
