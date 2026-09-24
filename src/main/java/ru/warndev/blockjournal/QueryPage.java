package ru.warndev.blockjournal;

import java.util.List;

public record QueryPage(List<Row> rows, boolean hasMore, long snapshotId) {
    public QueryPage {
        rows = List.copyOf(rows);
    }

    public long nextCursor() {
        return rows.isEmpty() ? 0 : rows.getLast().id();
    }

    public record Row(long id, BlockEntry entry) {
    }
}
