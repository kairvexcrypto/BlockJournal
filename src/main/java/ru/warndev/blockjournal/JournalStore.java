package ru.warndev.blockjournal;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JournalStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private final Connection connection;

    public JournalStore(Path database) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try {
            initialize();
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }

    private void initialize() throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA busy_timeout=3000");
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("PRAGMA synchronous=FULL");
            sql.execute("PRAGMA foreign_keys=ON");
            sql.execute("PRAGMA cache_size=-4096");
            int version;
            try (ResultSet result = sql.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : 0;
            }
            if (version > SCHEMA_VERSION) {
                throw new SQLException("Database schema is newer than this plugin: " + version);
            }
            if (version == 0) {
                migrate();
            }
        }
    }

    private void migrate() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS block_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_key TEXT NOT NULL UNIQUE,
                        occurred_at INTEGER NOT NULL,
                        actor_id TEXT,
                        actor_name TEXT NOT NULL COLLATE NOCASE,
                        world_id TEXT NOT NULL,
                        world_name TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        before_data TEXT NOT NULL,
                        after_data TEXT NOT NULL
                    )
                    """);
            sql.execute("CREATE INDEX IF NOT EXISTS idx_events_location ON block_events(world_id,x,z,y,id)");
            sql.execute("CREATE INDEX IF NOT EXISTS idx_events_time ON block_events(occurred_at,id)");
            sql.execute("CREATE INDEX IF NOT EXISTS idx_events_actor ON block_events(actor_id,id)");
            sql.execute("CREATE INDEX IF NOT EXISTS idx_events_name ON block_events(actor_name,id)");
            sql.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public int insert(List<BlockEntry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return 0;
        }
        connection.setAutoCommit(false);
        String query = """
                INSERT INTO block_events
                (event_key,occurred_at,actor_id,actor_name,world_id,world_name,x,y,z,action,before_data,after_data)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING
                """;
        int inserted = 0;
        try (PreparedStatement sql = connection.prepareStatement(query)) {
            for (BlockEntry entry : entries) {
                sql.setString(1, entry.eventId().toString());
                sql.setLong(2, entry.timestamp());
                sql.setString(3, entry.actorId() == null ? null : entry.actorId().toString());
                sql.setString(4, entry.actorName());
                sql.setString(5, entry.worldId().toString());
                sql.setString(6, entry.worldName());
                sql.setInt(7, entry.x());
                sql.setInt(8, entry.y());
                sql.setInt(9, entry.z());
                sql.setString(10, entry.action().name());
                sql.setString(11, entry.before());
                sql.setString(12, entry.after());
                sql.addBatch();
            }
            for (int count : sql.executeBatch()) {
                if (count > 0) {
                    inserted += count;
                } else if (count == Statement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
        return inserted;
    }

    public QueryPage query(QuerySpec spec, long beforeId, long snapshotId, int limit) throws SQLException {
        if (limit < 1 || limit > 30) {
            throw new IllegalArgumentException("limit");
        }
        if (snapshotId < 0 || beforeId < 0) {
            throw new IllegalArgumentException("cursor");
        }
        long snapshot = snapshotId == 0 ? maxId() : snapshotId;
        List<Object> parameters = new ArrayList<>();
        StringBuilder query = new StringBuilder("SELECT * FROM block_events WHERE world_id=?");
        parameters.add(spec.worldId().toString());
        bound(query, parameters, "x", spec.x(), spec.radius());
        bound(query, parameters, "y", spec.y(), spec.radius());
        bound(query, parameters, "z", spec.z(), spec.radius());
        query.append(" AND occurred_at>=? AND occurred_at<=? AND id<=?");
        parameters.add(spec.since());
        parameters.add(spec.until());
        parameters.add(snapshot);
        if (beforeId > 0) {
            query.append(" AND id<?");
            parameters.add(beforeId);
        }
        if (spec.actorId() != null) {
            query.append(" AND actor_id=?");
            parameters.add(spec.actorId().toString());
        }
        if (spec.actorName() != null) {
            query.append(" AND actor_name=? COLLATE NOCASE");
            parameters.add(spec.actorName());
        }
        if (!spec.actions().isEmpty()) {
            query.append(" AND action IN (");
            int count = 0;
            for (Action action : spec.actions()) {
                if (count++ > 0) {
                    query.append(',');
                }
                query.append('?');
                parameters.add(action.name());
            }
            query.append(')');
        }
        query.append(" ORDER BY id DESC LIMIT ?");
        parameters.add(limit + 1);
        List<QueryPage.Row> rows = new ArrayList<>();
        try (PreparedStatement sql = connection.prepareStatement(query.toString())) {
            sql.setQueryTimeout(5);
            for (int index = 0; index < parameters.size(); index++) {
                sql.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet result = sql.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
        }
        boolean more = rows.size() > limit;
        if (more) {
            rows.removeLast();
        }
        return new QueryPage(rows, more, snapshot);
    }

    private static void bound(StringBuilder query, List<Object> parameters, String column, int center, int radius) {
        query.append(" AND ").append(column).append(" BETWEEN ? AND ?");
        parameters.add((long) center - radius);
        parameters.add((long) center + radius);
    }

    private QueryPage.Row read(ResultSet result) throws SQLException {
        String actor = result.getString("actor_id");
        BlockEntry entry = new BlockEntry(
                UUID.fromString(result.getString("event_key")),
                result.getLong("occurred_at"),
                actor == null ? null : UUID.fromString(actor),
                result.getString("actor_name"),
                UUID.fromString(result.getString("world_id")),
                result.getString("world_name"),
                result.getInt("x"),
                result.getInt("y"),
                result.getInt("z"),
                Action.valueOf(result.getString("action")),
                result.getString("before_data"),
                result.getString("after_data"));
        return new QueryPage.Row(result.getLong("id"), entry);
    }

    public int prune(long cutoff, int limit) throws SQLException {
        if (limit < 1 || limit > 100000) {
            throw new IllegalArgumentException("limit");
        }
        String query = "DELETE FROM block_events WHERE id IN (SELECT id FROM block_events WHERE occurred_at<? ORDER BY occurred_at LIMIT ?)";
        try (PreparedStatement sql = connection.prepareStatement(query)) {
            sql.setLong(1, cutoff);
            sql.setInt(2, limit);
            return sql.executeUpdate();
        }
    }

    public long maxId() throws SQLException {
        try (Statement sql = connection.createStatement();
             ResultSet result = sql.executeQuery("SELECT COALESCE(MAX(id),0) FROM block_events")) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    public void checkpoint() throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA wal_checkpoint(PASSIVE)");
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
