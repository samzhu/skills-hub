package io.github.samzhu.skillshub.search;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * AC-3, AC-4 驗證：SearchProjection 監聽領域事件並正確呼叫 VectorStore。
 */
class SearchProjectionTest {

    private VectorStore vectorStore;
    private SearchProjection projection;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        projection = new SearchProjection(vectorStore);
    }

    @Test
    @DisplayName("AC-3: SkillCreatedEvent → VectorStore.add() 含正確 Document metadata")
    void onSkillCreated_addsDocumentWithCorrectMetadata() {
        var event = new SkillCreatedEvent(
                "skill-1",
                "docker-helper",
                "管理 Docker 容器",
                "sam",
                "DevOps"
        );

        projection.onSkillCreated(event);

        verify(vectorStore).add(argThat((List<Document> docs) -> {
            if (docs.size() != 1) return false;
            var doc = docs.get(0);
            var meta = doc.getMetadata();
            return "skill-1".equals(doc.getId())
                    && "docker-helper 管理 Docker 容器".equals(doc.getText())
                    && "skill-1".equals(meta.get("skillId"))
                    && "docker-helper".equals(meta.get("name"))
                    && "管理 Docker 容器".equals(meta.get("description"))
                    && "sam".equals(meta.get("author"))
                    && "DevOps".equals(meta.get("category"));
        }));
    }

    @Test
    @DisplayName("AC-4: SkillVersionPublishedEvent → delete+add 含 frontmatter metadata")
    void onVersionPublished_deletesAndAddsWithFrontmatterMetadata() {
        var frontmatter = Map.<String, Object>of(
                "name", "docker-helper",
                "description", "新版：管理 Docker 容器與 Compose",
                "author", "sam",
                "category", "DevOps",
                "version", "2.0.0",
                "risk_level", "LOW"
        );
        var event = new SkillVersionPublishedEvent(
                "skill-1",
                "2.0.0",
                "skills/skill-1/2.0.0.zip",
                1024L,
                frontmatter
        );

        projection.onVersionPublished(event);

        // delete must be called with the aggregateId
        verify(vectorStore).delete(List.of("skill-1"));

        // add must be called with updated Document from frontmatter
        verify(vectorStore).add(argThat((List<Document> docs) -> {
            if (docs.size() != 1) return false;
            var doc = docs.get(0);
            var meta = doc.getMetadata();
            return "skill-1".equals(doc.getId())
                    && "docker-helper 新版：管理 Docker 容器與 Compose".equals(doc.getText())
                    && "skill-1".equals(meta.get("skillId"))
                    && "docker-helper".equals(meta.get("name"))
                    && "新版：管理 Docker 容器與 Compose".equals(meta.get("description"))
                    && "sam".equals(meta.get("author"))
                    && "DevOps".equals(meta.get("category"));
        }));
    }
}
