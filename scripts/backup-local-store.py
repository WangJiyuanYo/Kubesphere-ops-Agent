"""在本机 Agent 重启前检查活动工作并配套在线备份 SQLite 与主密钥。"""
import json
from contextlib import closing
from pathlib import Path
import shutil
import sqlite3
import sys
import time

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
database = root / 'data/agent.db'
key = root / 'data/console-master.key'
if not database.is_file() or not key.is_file():
    raise SystemExit('未找到配套 SQLite 与主密钥，未创建或迁移数据库。')
with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)) as connection:
    active = connection.execute("SELECT count(*) FROM agent_run WHERE state NOT IN ('SUCCEEDED','FAILED','MANUAL_REQUIRED','CANCELLED')").fetchone()[0]
    chats = connection.execute("SELECT count(*) FROM agent_chat_turn WHERE state='RUNNING' AND lease_until>?", (int(time.time() * 1000),)).fetchone()[0]
    investigations = 0
    if connection.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='agent_discovered_task'").fetchone():
        investigations = connection.execute("SELECT count(*) FROM agent_discovered_task WHERE state IN ('QUEUED','RUNNING')").fetchone()[0]
    if active or chats or investigations:
        raise SystemExit('存在活动恢复、对话或调查，请等待完成后重启。')
    if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
        raise SystemExit('SQLite 完整性检查失败，未重启。')
    folder = root / 'data/backups' / ('platform-enable-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(time.time_ns()))
    folder.mkdir(parents=True, exist_ok=False)
    with closing(sqlite3.connect(folder / 'agent.db')) as destination:
        connection.backup(destination)
        if destination.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise SystemExit('备份完整性检查失败，未重启。')
    shutil.copy2(key, folder / 'console-master.key')
print(json.dumps({'backup': str(folder), 'integrity': 'ok', 'activeRuns': active, 'activeChats': chats, 'activeInvestigations': investigations}, ensure_ascii=False))
