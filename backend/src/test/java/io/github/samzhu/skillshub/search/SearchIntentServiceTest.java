package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchIntentServiceTest {

    @Test
    @DisplayName("AC-S171-5: SearchIntentService returns keyword fallback when search intent chat client is absent")
    void returnsKeywordFallbackWhenChatClientAbsent() {
        var service = new SearchIntentService(Optional.empty());

        var response = service.summarize("terraform");

        assertThat(response.summary()).isEqualTo("terraform");
        assertThat(response.concepts()).isEmpty();
    }
}
