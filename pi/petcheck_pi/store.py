"""Local event log in SQLite. Events wait here until they have been uploaded."""

from __future__ import annotations

import sqlite3
import threading
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class StoredEvent:
    id: str
    started_at_ms: int
    duration_ms: int
    type: str
    peak_db: float
    confidence: float
    synced: bool = False


class EventStore:
    def __init__(self, path: Path | str):
        self._lock = threading.Lock()
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.execute(
            """CREATE TABLE IF NOT EXISTS events (
                   id TEXT PRIMARY KEY,
                   started_at_ms INTEGER NOT NULL,
                   duration_ms INTEGER NOT NULL,
                   type TEXT NOT NULL,
                   peak_db REAL NOT NULL,
                   confidence REAL NOT NULL,
                   synced INTEGER NOT NULL DEFAULT 0
               )"""
        )
        self._db.execute("CREATE INDEX IF NOT EXISTS events_synced ON events (synced, started_at_ms)")
        self._db.commit()

    def add(self, event: StoredEvent) -> None:
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO events VALUES (?, ?, ?, ?, ?, ?, ?)",
                (event.id, event.started_at_ms, event.duration_ms, event.type, event.peak_db,
                 event.confidence, int(event.synced)),
            )
            self._db.commit()

    def unsynced(self, limit: int = 200) -> list[StoredEvent]:
        return self._select("WHERE synced = 0 ORDER BY started_at_ms LIMIT ?", (limit,))

    def recent(self, limit: int = 50) -> list[StoredEvent]:
        return self._select("ORDER BY started_at_ms DESC LIMIT ?", (limit,))

    def mark_synced(self, ids: list[str]) -> None:
        with self._lock:
            self._db.executemany("UPDATE events SET synced = 1 WHERE id = ?", [(i,) for i in ids])
            self._db.commit()

    def prune(self, older_than_ms: int) -> int:
        """Drops uploaded events older than the cut-off. Events not yet uploaded are always kept."""
        with self._lock:
            cursor = self._db.execute("DELETE FROM events WHERE synced = 1 AND started_at_ms < ?", (older_than_ms,))
            self._db.commit()
            return cursor.rowcount

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def _select(self, clause: str, args: tuple) -> list[StoredEvent]:
        with self._lock:
            rows = self._db.execute(
                f"SELECT id, started_at_ms, duration_ms, type, peak_db, confidence, synced FROM events {clause}", args
            ).fetchall()
        return [StoredEvent(r[0], r[1], r[2], r[3], r[4], r[5], bool(r[6])) for r in rows]
