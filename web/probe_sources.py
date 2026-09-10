# -*- coding: utf-8 -*-
"""并发探测 musicdl 全部音源：可用性 / 耗时 / 结果数"""
import io
import json
import contextlib
import threading
import time
import traceback

from musicdl import musicdl
from musicdl.modules import sources

CLIENTS = sorted(n for n in dir(sources) if n.endswith('MusicClient') and n != 'BaseMusicClient')
KEYWORD = '晴天'
results = {}
lock = threading.Lock()


def probe(name):
    rec = {'client': name, 'ok': False, 'seconds': None, 'total': 0, 'with_url': 0, 'error': None}
    t0 = time.time()
    try:
        buf = io.StringIO()
        mc = musicdl.MusicClient(
            music_sources=[name],
            init_music_clients_cfg={name: {'search_size_per_source': 4, 'disable_print': True}},
        )
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            res = mc.search(KEYWORD)
        items = (res or {}).get(name) or []
        rec['total'] = len(items)
        rec['with_url'] = sum(1 for it in items if getattr(it, 'download_url', None))
        rec['ok'] = rec['with_url'] > 0
    except Exception as err:  # noqa: BLE001
        rec['error'] = str(err)[:120]
    rec['seconds'] = round(time.time() - t0, 1)
    with lock:
        results[name] = rec
        print(json.dumps(rec, ensure_ascii=False), flush=True)


threads = [threading.Thread(target=probe, args=(n,), daemon=True) for n in CLIENTS]
t0 = time.time()
for t in threads:
    t.start()
# 全局截止：150s 后放弃仍在挂起的源（守护线程直接抛弃，不逐一 join）
DEADLINE = 150
while time.time() - t0 < DEADLINE:
    if len(results) >= len(CLIENTS):
        break
    time.sleep(2)
print('PHASE_DONE elapsed', round(time.time() - t0, 1), 'finished', len(results), '/', len(CLIENTS))
for n in CLIENTS:
    if n not in results:
        results[n] = {'client': n, 'ok': False, 'seconds': None, 'total': 0,
                      'with_url': 0, 'error': 'timeout(150s)'}
with open('probe_results.json', 'w', encoding='utf-8') as f:
    json.dump(results, f, ensure_ascii=False, indent=1)
print('ALL_DONE')
