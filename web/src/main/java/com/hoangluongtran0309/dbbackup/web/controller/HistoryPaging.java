package com.hoangluongtran0309.dbbackup.web.controller;

/**
 * How the backup and restore lists are paged. One place, so the two lists
 * cannot drift apart.
 */
final class HistoryPaging {

    /** Rows per page: a screenful or two, and a query that stays small however long the history. */
    static final int PAGE_SIZE = 50;

    private HistoryPaging() {
    }

    /** A page number from the query string; anything below 1 is the first page. */
    static int page(int requested) {
        return Math.max(requested, 1);
    }
}
