package io.github.samzhu.skillshub.shared.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test helper（S023-T07）— 在 @Transactional boundary 內 publish event。
 *
 * <p>必要原因：S023 後 9 個 listener 改為 {@code @ApplicationModuleListener}
 * （內含 {@code @TransactionalEventListener(AFTER_COMMIT)}）。預設行為：若 publish
 * 發生在 TX 外，AFTER_COMMIT listener 會被 silently drop（{@code fallbackExecution=false}）。
 * 既有 test 直接呼叫 {@code publisher.publishEvent(...)} 在 TX 外，listener 不觸發 →
 * 整批 integration test 失敗。
 *
 * <p>修法：本 helper 提供 {@code @Transactional} 包裝，test 可呼叫 {@code publishInTx}
 * 確保 listener 觸發；async 完成需另行用 Awaitility 等待。
 *
 * <p>由各 test class 自己宣告 {@code @Autowired TestEventTxHelper helper} 即可使用
 * （已 {@code @Component} 註冊在 main classpath，不依賴 @TestConfiguration）。
 *
 * <p><b>注意</b>：本 class 放 main test src，因 Spring Component scan 會掃；test only
 * helper 通常放 test src，但 Spring Boot 4 + spring-modulith-starter-test 在 Modulith
 * verify 時對 test classpath 的處理較嚴格，放 main src 但限定 test 用即可避免 verify warning。
 *
 * <p>S024 後若 Skill 改為 stateful aggregate，業務層 service 都會是 @Transactional 入口，
 * 此 helper 將不再需要；屆時刪除。
 */
@Component
public class TestEventTxHelper {

    private final ApplicationEventPublisher publisher;

    public TestEventTxHelper(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** 在 @Transactional 內 publish event；commit 後 AFTER_COMMIT listener 觸發。 */
    @Transactional
    public void publishInTx(Object event) {
        publisher.publishEvent(event);
    }
}
