import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Lock


class ConversationStore:
    def __init__(self, path: Path):
        self.path = path
        self._lock = Lock()

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    source TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(session_id) REFERENCES sessions(id)
                );
                CREATE TABLE IF NOT EXISTS session_contexts (
                    session_id TEXT PRIMARY KEY,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    FOREIGN KEY(session_id) REFERENCES sessions(id)
                );
                CREATE INDEX IF NOT EXISTS idx_sessions_created_at
                    ON sessions(created_at DESC);
                """
            )

    def create_session(self, session_id: str) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                "INSERT OR IGNORE INTO sessions(id, created_at) VALUES (?, ?)",
                (session_id, _now()),
            )

    def latest_session_id(self) -> str | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT id FROM sessions ORDER BY created_at DESC LIMIT 1"
            ).fetchone()
        return str(row[0]) if row else None

    def add_message(self, session_id: str, role: str, content: str, source: str) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO messages(session_id, role, content, source, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (session_id, role, content, source, _now()),
            )

    def recent_messages(self, session_id: str, limit: int = 12) -> list[dict[str, str]]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT role, content FROM messages
                WHERE session_id = ?
                ORDER BY id DESC LIMIT ?
                """,
                (session_id, limit),
            ).fetchall()
        return [{"role": row[0], "content": row[1]} for row in reversed(rows)]

    def message_events(
        self,
        session_id: str,
        after_id: int = 0,
        limit: int = 100,
    ) -> list[dict[str, str | int]]:
        with self._lock, self._connect() as connection:
            if after_id:
                rows = connection.execute(
                    """
                    SELECT id, role, content, source, created_at FROM messages
                    WHERE session_id = ? AND id > ?
                    ORDER BY id ASC LIMIT ?
                    """,
                    (session_id, after_id, limit),
                ).fetchall()
            else:
                rows = list(
                    reversed(
                        connection.execute(
                            """
                            SELECT id, role, content, source, created_at FROM messages
                            WHERE session_id = ?
                            ORDER BY id DESC LIMIT ?
                            """,
                            (session_id, limit),
                        ).fetchall()
                    )
                )
        return [
            {
                "id": row[0],
                "role": row[1],
                "content": row[2],
                "source": row[3],
                "created_at": row[4],
            }
            for row in rows
        ]

    def set_session_context(
        self,
        session_id: str,
        content: str,
        ttl_minutes: int = 180,
    ) -> None:
        now = datetime.now(timezone.utc)
        expires = now + timedelta(minutes=max(1, ttl_minutes))
        self.create_session(session_id)
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO session_contexts(
                    session_id, content, created_at, expires_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    content=excluded.content,
                    created_at=excluded.created_at,
                    expires_at=excluded.expires_at
                """,
                (
                    session_id,
                    content,
                    now.isoformat(),
                    expires.isoformat(),
                ),
            )

    def active_session_context(self, session_id: str) -> str | None:
        now = datetime.now(timezone.utc).isoformat()
        with self._lock, self._connect() as connection:
            row = connection.execute(
                """
                SELECT content FROM session_contexts
                WHERE session_id = ? AND expires_at > ?
                """,
                (session_id, now),
            ).fetchone()
        return str(row[0]) if row else None

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
