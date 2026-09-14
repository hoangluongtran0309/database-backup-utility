package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;

/**
 * One page of a newest-first history.
 *
 * <p>No total and no page count: counting the whole table on every page view
 * would cost exactly what paging exists to avoid, and "newer" and "older" are
 * all the console offers. {@code hasOlder} is learned by fetching one row more
 * than the page holds.
 *
 * @param page  1-based
 * @param items newest first, at most one page's worth
 */
public record HistoryPage<T>(List<T> items, int page, boolean hasOlder) {

    public HistoryPage {
        if (page < 1) {
            throw new IllegalArgumentException("Pages are numbered from 1, not " + page);
        }
        items = List.copyOf(items);
    }

    /** Every page after the first has a newer one before it. */
    public boolean hasNewer() {
        return page > 1;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
