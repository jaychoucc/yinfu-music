"""
音符 Note —— 网页端 Flask 后端
基于 musicdl 2.x 聚合全部 58 个音源，提供 SSE 逐源异步搜索、音频代理、歌词与下载。

说明：
- musicdl 2.x 的公开接口为 MusicClient(music_sources=[...]).search(keyword)，
  返回 dict[客户端类名 -> list[SongInfo]]。
- 每个音源独立线程并发搜索（各自持有独立 client），谁先完成谁先上屏（异步加载）。
- 为避免 musicdl 内部 rich 进度条污染 stdout，搜索调用期间临时重定向 stdout/stderr。
"""
import os
import sys
import json
import time
import uuid
import queue
import threading
import contextlib
import logging
import requests
from urllib.parse import unquote
from flask import Flask, request, Response, send_from_directory, stream_with_context

from musicdl import musicdl

# ---------------------------------------------------------------------------
# 基础配置
# ---------------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(HERE, 'static')
DOWNLOAD_DIR = os.path.join(HERE, 'downloads')
os.makedirs(DOWNLOAD_DIR, exist_ok=True)

HOST = '127.0.0.1'
PORT = 58652

# 音源短名 -> musicdl 客户端类名 / 显示名称 / 分组 / 默认是否启用
# group: core=主力音源  cn=国内站点与聚合源  radio=播客电台  overseas=海外平台
_SOURCE_TABLE = [
    # --- 主力 ---
    ('migu',        'MiguMusicClient',        '咪咕音乐',       'core'),
    ('netease',     'NeteaseMusicClient',     '网易云音乐',     'core'),
    ('kuwo',        'KuwoMusicClient',        '酷我音乐',       'core'),
    ('qq',          'QQMusicClient',          'QQ音乐',        'core'),
    ('kugou',       'KugouMusicClient',       '酷狗音乐',       'core'),
    ('qianqian',    'QianqianMusicClient',    '千千音乐',       'core'),
    # --- 国内站点 / 聚合源 ---
    ('gdstudio',    'GDStudioMusicClient',    'GD音乐台',       'cn'),
    ('gequbao',     'GequbaoMusicClient',     '歌曲宝',         'cn'),
    ('gequhai',     'GequhaiMusicClient',     '歌曲海',         'cn'),
    ('fivesing',    'FiveSingMusicClient',    '5sing原创',      'cn'),
    ('bilibili',    'BilibiliMusicClient',    'B站音频',        'cn'),
    ('yinyuedao',   'YinyuedaoMusicClient',   '音乐岛',         'cn'),
    ('yinyueku',    'YinyuekuMusicClient',    '音乐库',         'cn'),
    ('xiageba',     'XiagebaMusicClient',     '下歌吧',         'cn'),
    ('xiaobai',     'XiaoBaiMusicClient',     '小白音乐',       'cn'),
    ('bodian',      'BodianMusicClient',      '波点音乐',       'cn'),
    ('soda',        'SodaMusicClient',        '汽水音乐',       'cn'),
    ('mitu',        'MituMusicClient',        '米兔音乐',       'cn'),
    ('myfreemp3',   'MyFreeMP3MusicClient',   'MyFreeMP3',      'cn'),
    ('jbsou',       'JBSouMusicClient',       'JBSou',          'cn'),
    ('tunehub',     'TuneHubMusicClient',     'TuneHub',        'cn'),
    ('mp3juice',    'MP3JuiceMusicClient',    'MP3Juice',       'cn'),
    ('itingwa',     'ITingWaMusicClient',     '爱听蛙',         'cn'),
    ('htqyy',       'HTQYYMusicClient',       'HTQYY音乐',      'cn'),
    ('kkws',        'KKWSMusicClient',        'KKWS音乐',       'cn'),
    ('liziyy',      'LiziYYMusicClient',      '栗子YY',         'cn'),
    ('mgmp3',       'MGMP3MusicClient',       'MGMP3',          'cn'),
    ('sgogo',       'SgogoMusicClient',       'Sgogo音乐',      'cn'),
    ('twot58',      'TwoT58MusicClient',      '2T58音乐',       'cn'),
    ('xmfwav',      'XMFWAVMusicClient',      'XMFWAV',         'cn'),
    ('fangpi',      'FangpiMusicClient',      'Fangpi音乐',     'cn'),
    ('buguyy',      'BuguyyMusicClient',      '不菇音乐',       'cn'),
    ('build',       'BuildMusicClient',       'Build音乐',      'cn'),
    ('fivesong',    'FiveSongMusicClient',    'FiveSong',       'cn'),
    ('livepoo',     'LivePOOMusicClient',     'LivePOO',        'cn'),
    ('moov',        'MOOVMusicClient',        'MOOV音乐',       'cn'),
    ('lrts',        'LRTSMusicClient',        'LRTS电台',       'cn'),
    # --- 播客 / 电台 ---
    ('lizhi',       'LizhiMusicClient',       '荔枝FM',         'radio'),
    ('qingting',    'QingtingMusicClient',    '蜻蜓FM',         'radio'),
    ('ximalaya',    'XimalayaMusicClient',    '喜马拉雅',       'radio'),
    # --- 海外平台 ---
    ('youtube',     'YouTubeMusicClient',     'YouTube Music',  'overseas'),
    ('soundcloud',  'SoundCloudMusicClient',  'SoundCloud',     'overseas'),
    ('spotify',     'SpotifyMusicClient',     'Spotify',        'overseas'),
    ('apple',       'AppleMusicClient',       'Apple Music',    'overseas'),
    ('itunes',      'ITunesMusicClient',      'iTunes',         'overseas'),
    ('deezer',      'DeezerMusicClient',      'Deezer',         'overseas'),
    ('tidal',       'TIDALMusicClient',       'TIDAL',          'overseas'),
    ('qobuz',       'QobuzMusicClient',       'Qobuz',          'overseas'),
    ('joox',        'JooxMusicClient',        'JOOX',           'overseas'),
    ('jiosaavn',    'JioSaavnMusicClient',    'JioSaavn',       'overseas'),
    ('jamendo',     'JamendoMusicClient',     'Jamendo',        'overseas'),
    ('audius',      'AudiusMusicClient',      'Audius',         'overseas'),
    ('ccmixter',    'CCMixterMusicClient',    'ccMixter',       'overseas'),
    ('opengameart', 'OpenGameArtMusicClient', 'OpenGameArt',    'overseas'),
    ('wikimedia',   'WikimediaCommonsMusicClient', '维基共享',  'overseas'),
    ('streetvoice', 'StreetVoiceMusicClient', 'StreetVoice',    'overseas'),
    ('suno',        'SunoMusicClient',        'Suno AI',        'overseas'),
]

# 实测默认启用的音源（probe_sources.py：2026-09-09 探测，全部 ≤ 22s 且有带播放地址的结果）
DEFAULT_ENABLED = {
    'migu', 'myfreemp3', 'buguyy', 'netease', 'bilibili', 'gequhai',
    'apple', 'fivesing', 'bodian', 'twot58', 'mitu', 'soda',
    'yinyueku', 'kuwo', 'kugou',
}

SUPPORTED_SOURCES = {}
SOURCE_ORDER = []
for _short, _cls, _label, _group in _SOURCE_TABLE:
    if _label is None:
        continue
    SUPPORTED_SOURCES[_short] = {
        'client': _cls, 'label': _label, 'group': _group,
        'default': _short in DEFAULT_ENABLED,
    }
    SOURCE_ORDER.append(_short)
# 客户端类名 -> 短名
CLIENT_TO_SHORT = {v['client']: k for k, v in SUPPORTED_SOURCES.items()}

SEARCH_SIZE_PER_SOURCE = 8
# 全源逐源异步：单源搜索线程独立计时；整体看门狗兜底（海外慢源可能 2 分钟以上）
SEARCH_WATCHDOG = 45
RESULT_EXT_TO_MIME = {
    'mp3': 'audio/mpeg', 'flac': 'audio/flac', 'wav': 'audio/wav',
    'm4a': 'audio/mp4', 'aac': 'audio/aac', 'ape': 'audio/x-ape',
    'ogg': 'audio/ogg', 'mp4': 'audio/mp4',
}

# 屏蔽 musicdl 与 urllib 的警告，避免污染 stdout
requests.packages.urllib3.disable_warnings()
logging.getLogger('urllib3').setLevel(logging.ERROR)


# ---------------------------------------------------------------------------
# 抑制 musicdl 的 rich 进度条输出
#
# 关键：不能用"锁 + 每次新建 devnull"的做法——那会把所有音源的搜索
# 串行化（锁内做网络请求），实测 15 源会从 20s 劣化到 180s+ 全部超时。
# 正确做法是全程把 sys.stdout/sys.stderr 指向一个"哑写入器"：
#   - write() 是空操作，天然线程安全，无需加锁
#   - 不会被任何线程关闭，避免 "I/O operation on closed file"
# ---------------------------------------------------------------------------
# 全局唯一的 devnull：打开一次、永不关闭。
# 相比"每次新建 + 加锁"的方案，它既线程安全（不关闭就不会有并发 I/O 错误），
# 又不会把各音源的搜索串行化（加锁会让 15 源从 20s 劣化到 180s+ 全部超时）。
_DEVNULL = open(os.devnull, 'w')


@contextlib.contextmanager
def _suppress_musicdl_output():
    """幂等地把 stdout/stderr 切到全局 devnull；退出时不还原（避免并发竞态）。"""
    sys.stdout = _DEVNULL
    sys.stderr = _DEVNULL
    try:
        yield
    finally:
        pass


# ---------------------------------------------------------------------------
# musicdl 客户端管理（按音源类名懒加载单例，支持 58 源并发搜索）
# ---------------------------------------------------------------------------
class ClientManager:
    def __init__(self):
        self._lock = threading.Lock()
        self._clients = {}

    def client_for(self, class_name):
        with self._lock:
            client = self._clients.get(class_name)
            if client is None:
                client = musicdl.MusicClient(
                    music_sources=[class_name],
                    init_music_clients_cfg={
                        class_name: {
                            'search_size_per_source': SEARCH_SIZE_PER_SOURCE,
                            'disable_print': True,
                        }
                    },
                )
                self._clients[class_name] = client
            return client


MANAGER = ClientManager()


# ---------------------------------------------------------------------------
# 内存注册表：token -> SongInfo
# ---------------------------------------------------------------------------
class TrackRegistry:
    def __init__(self):
        self._lock = threading.Lock()
        self._tracks = {}

    def add(self, song_info, source_short):
        token = uuid.uuid4().hex[:16]
        headers = dict(getattr(song_info, 'default_download_headers', {}) or {})
        cookies = dict(getattr(song_info, 'default_download_cookies', {}) or {})
        with self._lock:
            self._tracks[token] = {
                'song_info': song_info,
                'source': source_short,
                'headers': headers,
                'cookies': cookies,
            }
        return token

    def get(self, token):
        with self._lock:
            return self._tracks.get(token)

    def update_url(self, token, url):
        with self._lock:
            entry = self._tracks.get(token)
            if entry:
                entry['song_info'].download_url = url


REGISTRY = TrackRegistry()


# ---------------------------------------------------------------------------
# SSE 序列化
# ---------------------------------------------------------------------------
def _s(v):
    return '' if v is None else str(v)


def _track_payload(song_info, token, source_short):
    ext = _s(song_info.ext).lower().lstrip('.')
    source_meta = SUPPORTED_SOURCES.get(source_short, {})
    # musicdl 搜索阶段 bitrate/codec 通常为 None，仅 ext 可靠
    bitrate = int(getattr(song_info, 'bitrate', 0) or 0)
    lossless = ext in {'flac', 'wav', 'ape', 'alac'}
    return {
        'token': token,
        'source': source_short,
        'source_label': source_meta.get('label', source_short),
        'song_name': _s(song_info.song_name) or '未知曲目',
        'singers': _s(song_info.singers) or '未知艺人',
        'album': _s(song_info.album),
        'ext': ext,
        'file_size': _s(song_info.file_size),
        'duration': _s(song_info.duration) or '0:00',
        'cover_url': _s(song_info.cover_url),
        'has_lyric': bool(getattr(song_info, 'lyric', None)),
        'lossless': lossless,
        'bitrate': bitrate,
        # 统一品质标签：无损 / 高品质(>=320kbps) / 标准
        'quality': (
            '无损' if lossless
            else '高品质' if bitrate >= 320
            else '标准'
        ),
    }


# ---------------------------------------------------------------------------
# 逐源异步流式搜索 + 失效源剔除：谁先完成谁先上屏
# 每个音源搜索完成后先做一次轻量 HEAD 探测，失效的整组丢弃
# ---------------------------------------------------------------------------
def _search_worker(source_short, class_name, keyword, out_queue):
    """单音源搜索线程：完成后把 (源, 类型, 载荷) 放入队列。"""
    t0 = time.time()
    try:
        with _suppress_musicdl_output():
            res = MANAGER.client_for(class_name).search(keyword)
        songs = (res or {}).get(class_name) or []
        out_queue.put((source_short, 'ok', songs, round(time.time() - t0, 1)))
    except Exception as err:  # noqa: BLE001
        out_queue.put((source_short, 'error', str(err)[:160], round(time.time() - t0, 1)))


def search_stream(keyword, source_shorts):
    valid = [s for s in SOURCE_ORDER if s in source_shorts]
    if not valid:
        yield 'event: done\ndata: {"count": 0}\n\n'
        return

    for source_short in valid:
        yield 'event: source_start\ndata: %s\n\n' % json.dumps({
            'source': source_short,
            'label': SUPPORTED_SOURCES[source_short]['label'],
        }, ensure_ascii=False)

    out_queue = queue.Queue()
    threads = []
    for source_short in valid:
        t = threading.Thread(
            target=_search_worker,
            args=(source_short, SUPPORTED_SOURCES[source_short]['client'], keyword, out_queue),
            daemon=True,
        )
        t.start()
        threads.append(t)

    finished = set()
    total = 0
    deadline = time.time() + SEARCH_WATCHDOG
    while len(finished) < len(valid) and time.time() < deadline:
        try:
            source_short, kind, payload, elapsed = out_queue.get(timeout=1.0)
        except queue.Empty:
            continue
        finished.add(source_short)
        if kind == 'error':
            yield 'event: source_error\ndata: %s\n\n' % json.dumps(
                {'source': source_short, 'message': payload or '搜索失败'},
                ensure_ascii=False)
            continue

        songs = payload
        if not songs:
            yield 'event: source_done\ndata: %s\n\n' % json.dumps(
                {'source': source_short, 'count': 0, 'seconds': elapsed,
                 'filtered': False, 'reason': 'no_result'},
                ensure_ascii=False)
            continue

        # 关键：探测源可用性，失效源整组丢弃
        alive = _is_source_alive(source_short, songs)
        if not alive:
            yield 'event: source_error\ndata: %s\n\n' % json.dumps(
                {'source': source_short, 'message': '该源链接已失效，结果已自动剔除'},
                ensure_ascii=False)
            yield 'event: source_done\ndata: %s\n\n' % json.dumps(
                {'source': source_short, 'count': 0, 'seconds': elapsed,
                 'filtered': True, 'reason': 'all_urls_dead'},
                ensure_ascii=False)
            continue

        seen = set()
        count = 0
        for song in songs:
            key = (_s(song.song_name), _s(song.singers))
            if key in seen:
                continue
            seen.add(key)
            token = REGISTRY.add(song, source_short)
            yield 'event: result\ndata: %s\n\n' % json.dumps(
                _track_payload(song, token, source_short), ensure_ascii=False)
            count += 1
            total += 1
        yield 'event: source_done\ndata: %s\n\n' % json.dumps(
            {'source': source_short, 'count': count, 'seconds': elapsed,
             'filtered': False},
            ensure_ascii=False)

    # 看门狗兜底：仍未完成的源标记超时
    for source_short in valid:
        if source_short not in finished:
            yield 'event: source_error\ndata: %s\n\n' % json.dumps(
                {'source': source_short, 'message': '搜索超时'}, ensure_ascii=False)

    yield 'event: done\ndata: {"count": %d}\n\n' % total


# ---------------------------------------------------------------------------
# 失效源剔除：搜索时对每个有结果返回的源做轻量 HEAD 探测，失效源整组丢弃
# 策略：只对**第 1 首**做 HEAD（音乐下载链接通常同源同 token，1 个失败即整源失效），
# 耗时固定 ≤ 3.5s，对正常源几乎无感。
# ---------------------------------------------------------------------------
def _probe_url_alive(url, headers, cookies, timeout=3.5):
    """HEAD 探测 URL 是否可用（短超时，仅用于搜索时过滤失效源）。"""
    if not url or not url.startswith('http'):
        return False
    try:
        r = requests.head(
            url, headers=headers or {}, cookies=cookies or {},
            timeout=timeout, verify=False, allow_redirects=True,
        )
        # HEAD 返回 200/206 才算可用；4xx/5xx 视为失效
        return 200 <= r.status_code < 400
    except Exception:  # noqa: BLE001
        return False


def _is_source_alive(source_short, songs):
    """取第 1 个 sample URL 探测，失败即整源失效。"""
    for s in songs:
        url = getattr(s, 'download_url', None)
        if not url:
            continue
        headers = getattr(s, 'default_download_headers', {}) or {}
        cookies = getattr(s, 'default_download_cookies', {}) or {}
        return _probe_url_alive(url, headers, cookies)
    return True  # 没有 url 信息时保守保留


# ---------------------------------------------------------------------------
# Flask 应用
# ---------------------------------------------------------------------------
app = Flask(__name__, static_folder=None)


@app.route('/')
def index():
    return send_from_directory(STATIC_DIR, 'index.html')


@app.route('/static/<path:filename>')
def static_files(filename):
    return send_from_directory(STATIC_DIR, filename)


@app.route('/api/sources')
def api_sources():
    """全部可用音源清单（前端动态渲染音源栏）。"""
    return Response(
        json.dumps({
            'sources': [
                {
                    'id': s,
                    'label': info['label'],
                    'group': info['group'],
                    'default': info['default'],
                }
                for s, info in ((k, SUPPORTED_SOURCES[k]) for k in SOURCE_ORDER)
            ],
        }, ensure_ascii=False),
        mimetype='application/json',
    )


@app.route('/api/search')
def api_search():
    keyword = (request.args.get('q') or '').strip()
    raw_sources = (request.args.get('sources') or '').strip()
    sources = [s.strip() for s in raw_sources.split(',') if s.strip() in SUPPORTED_SOURCES]
    if not sources:
        sources = [s for s in SOURCE_ORDER if SUPPORTED_SOURCES[s]['default']]
    if not keyword:
        return json.dumps({'error': '请输入搜索关键词'}), 400, {'Content-Type': 'application/json'}

    @stream_with_context
    def generate():
        yield 'retry: 10000\n\n'
        for msg in search_stream(keyword, sources):
            yield msg

    return Response(
        generate(),
        mimetype='text/event-stream',
        headers={'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no'},
    )


@app.route('/api/stream')
def api_stream():
    token = request.args.get('id', '').strip()
    entry = REGISTRY.get(token)
    if not entry:
        return 'expired', 404

    # 注意：这里刻意不做"用 musicdl 重新搜索拿新链接"。
    # 之前的实现会在播放请求里同步重搜（耗时几十秒且阻塞工作线程），
    # 前端一旦失败自动切下一首就会形成请求雪崩，最终把 Flask 打满导致浏览器卡死。
    # 现在改为快速失败：链接失效就让前端明确感知并自行处理。
    song = entry['song_info']
    url = getattr(song, 'download_url', None)
    if not isinstance(url, str) or not url.startswith('http'):
        return _audio_error('该曲目没有可用的播放地址')

    upstream_headers = dict(entry['headers'])
    range_header = request.headers.get('Range')
    if range_header:
        upstream_headers['Range'] = range_header

    try:
        up = requests.get(
            url, headers=upstream_headers, cookies=entry['cookies'],
            stream=True, timeout=(8, 20), verify=False,
        )
    except Exception as err:  # noqa: BLE001
        return _audio_error('upstream error: %s' % err)

    # 上游非成功状态（403 防盗链 / 404 过期 / 5xx）：直接返回 JSON 错误。
    # 绝不能伪装成 audio/mpeg 返回，否则 <audio> 会一直空等并反复重试 → 浏览器卡死。
    if up.status_code >= 400:
        up.close()
        return _audio_error('音源返回 %s（链接可能已过期或受防盗链限制）' % up.status_code,
                            status=502)

    ext = (str(song.ext) or 'mp3').lstrip('.').lower()
    content_type = RESULT_EXT_TO_MIME.get(ext, 'application/octet-stream')
    # 上游没给 Content-Type 或给了非音频类型时，以扩展名推断的为准
    up_ct = (up.headers.get('Content-Type') or '').lower()
    if up_ct and not up_ct.startswith('audio') and 'octet-stream' not in up_ct:
        # 上游返回的是网页/错误页，说明不是真音频
        up.close()
        return _audio_error('音源返回了非音频内容（%s）' % up_ct.split(';')[0], status=502)

    resp_headers = {
        'Content-Type': content_type,
        'Accept-Ranges': 'bytes',
        'Cache-Control': 'no-cache',
    }
    for h in ('Content-Length', 'Content-Range'):
        if h in up.headers:
            resp_headers[h] = up.headers[h]

    def generate():
        try:
            for chunk in up.iter_content(chunk_size=64 * 1024):
                if chunk:
                    yield chunk
        finally:
            up.close()

    return Response(
        stream_with_context(generate()),
        status=up.status_code,
        headers=resp_headers,
    )


def _audio_error(message, status=502):
    """音频请求失败时返回 JSON 错误（而不是伪装成音频），让前端能立即感知。"""
    return Response(
        json.dumps({'error': message}, ensure_ascii=False),
        status=status,
        mimetype='application/json',
    )


@app.route('/api/lyric')
def api_lyric():
    token = request.args.get('id', '').strip()
    entry = REGISTRY.get(token)
    if not entry:
        return '', 404
    lyric = getattr(entry['song_info'], 'lyric', '') or ''
    return Response(lyric, mimetype='text/plain; charset=utf-8')


def _safe_name(name):
    name = name or 'track'
    name = ''.join(c if c not in '\\/:*?"<>|' else '_' for c in name).strip()
    return name[:120] or 'track'


@app.route('/api/download')
def api_download():
    token = request.args.get('id', '').strip()
    title = unquote(request.args.get('title') or '').strip()
    entry = REGISTRY.get(token)
    if not entry:
        return json.dumps({'error': '曲目已过期，请重新搜索'}), 404, {'Content-Type': 'application/json'}

    # 同样不做阻塞式重搜，避免请求雪崩
    song = entry['song_info']
    url = getattr(song, 'download_url', None)
    if not isinstance(url, str) or not url.startswith('http'):
        return json.dumps({'error': '该平台暂不可用'}), 404, {'Content-Type': 'application/json'}

    ext = (str(song.ext) or 'mp3').lstrip('.').lower()
    name = title or '%s - %s' % (_safe_name(_s(song.song_name)), _safe_name(_s(song.singers)))
    filename = name if name.lower().endswith('.%s' % ext) else '%s.%s' % (name, ext)

    try:
        up = requests.get(
            url, headers=entry['headers'], cookies=entry['cookies'],
            stream=True, timeout=(8, 20), verify=False,
        )
        if up.status_code >= 400:
            up.close()
            return json.dumps({'error': '音源返回 %s，链接可能已过期' % up.status_code}), \
                502, {'Content-Type': 'application/json'}
    except Exception as err:  # noqa: BLE001
        return json.dumps({'error': str(err)}), 502, {'Content-Type': 'application/json'}

    resp_headers = {
        'Content-Type': RESULT_EXT_TO_MIME.get(ext, 'application/octet-stream'),
        'Content-Disposition': "attachment; filename*=UTF-8''%s" % filename,
    }
    if 'Content-Length' in up.headers:
        resp_headers['Content-Length'] = up.headers['Content-Length']

    def generate():
        try:
            for chunk in up.iter_content(chunk_size=256 * 1024):
                if chunk:
                    yield chunk
        finally:
            up.close()

    return Response(stream_with_context(generate()), headers=resp_headers)


if __name__ == '__main__':
    print('\n  🎵  音符 Note 运行中：http://%s:%d\n' % (HOST, PORT))
    app.run(host=HOST, port=PORT, threaded=True, debug=False)
