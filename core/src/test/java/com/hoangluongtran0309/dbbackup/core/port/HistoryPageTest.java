package com.hoangluongtran0309.dbbackup.core.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class HistoryPageTest {

    @Test
    void theFirstPageHasNothingNewer() {
        assertThat(new HistoryPage<>(List.of("a"), 1, true).hasNewer()).isFalse();
        assertThat(new HistoryPage<>(List.of("a"), 2, false).hasNewer()).isTrue();
    }

    @Test
    void pagesAreNumberedFromOne() {
        assertThatThrownBy(() -> new HistoryPage<>(List.of(), 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void holdsACopyOfItsItems() {
        List<String> items = new ArrayList<>(List.of("a"));
        HistoryPage<String> page = new HistoryPage<>(items, 1, false);

        items.add("b");

        assertThat(page.items()).containsExactly("a");
    }
}
