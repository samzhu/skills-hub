package io.github.samzhu.skillshub.community;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S096f2-T02 — Collection command endpoints（per spec §4.1）。
 *
 * <p>2 endpoints：
 * <ul>
 *   <li>{@code POST /api/v1/collections}              — create（驗 skillIds 全 PUBLISHED）</li>
 *   <li>{@code POST /api/v1/collections/{id}/install} — record install event + 回 N 個 download URLs</li>
 * </ul>
 *
 * <p>Owner 抽 sub 透過 {@link io.github.samzhu.skillshub.shared.security.CurrentUserProvider}
 * 在 service 內處理；controller 純 routing + body / path mapping。Exception 翻譯由
 * {@code GlobalExceptionHandler} 統一。
 */
@RestController
@RequestMapping("/api/v1/collections")
class CollectionCommandController {

    private final CollectionService service;

    CollectionCommandController(CollectionService service) {
        this.service = service;
    }

    /** AC-1/2/3/4 — 建立 Collection；body 不合 / skillIds 全 PUBLISHED 失敗則由 GlobalExceptionHandler 翻 400。 */
    @PostMapping
    ResponseEntity<Map<String, String>> create(@RequestBody CreateCollectionBody body) {
        var id = service.create(body.name(), body.description(), body.category(), body.skillIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    /** AC-7/8 — install；不存在 collection 由 GlobalExceptionHandler 翻 404。 */
    @PostMapping("/{id}/install")
    InstallResponse install(@PathVariable String id) {
        return new InstallResponse(service.install(id));
    }

    record CreateCollectionBody(String name, String description, String category, List<String> skillIds) {}

    record InstallResponse(List<String> downloadUrls) {}
}
