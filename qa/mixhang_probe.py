# -*- coding: utf-8 -*-
"""
实测「混账-周柏豪」在 App fallback 链(migu→kuwo→qq→kugou→myfreemp3→joox→netease→apple)
每一环的 search 命中 + resolve 表现,定位"还是只有 30 秒"的来源。

复刻 MiguMusicSource 的 search + listen-url 解密逻辑(与 Kotlin 一一对应)。
时长判定:HEAD Content-Length / URL 码率特征。
"""
import json
import struct
import sys
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
TIMEOUT = 15

MIGU_HEADERS = {
    "User-Agent": UA,
    "Accept": "application/json, text/plain, */*",
    "Origin": "https://h5.nf.migu.cn",
    "Referer": "https://h5.nf.migu.cn/",
    "ua": "Android_migu",
    "version": "6.8.8",
    "channel": "014021I",
    "subchannel": "014021I",
}

MIGU_KEY = b"Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP"


def http_get(url, headers=None, timeout=TIMEOUT):
    req = urllib.request.Request(url, headers=headers or {"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def http_get_json(url, headers=None, timeout=TIMEOUT):
    return json.loads(http_get(url, headers, timeout).decode("utf-8", "replace"))


def head_length(url, headers=None):
    """HEAD 拿 Content-Length;失败返回 None"""
    req = urllib.request.Request(url, headers=headers or {"User-Agent": UA}, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return int(r.headers.get("Content-Length", 0) or 0)
    except Exception:
        # 有些服务器不支持 HEAD,用 Range GET
        try:
            req = urllib.request.Request(url, headers={**(headers or {"User-Agent": UA}), "Range": "bytes=0-1"})
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                cr = r.headers.get("Content-Range", "")
                if "/" in cr:
                    return int(cr.split("/")[-1])
        except Exception:
            pass
    return None


def migu_decrypt(raw: bytes) -> str:
    if len(raw) < 4:
        return raw.decode("utf-8", "replace")
    if raw[0] == 0xAB and raw[1] == 0xCD and raw[2] == 0x01:
        seed = raw[3]
        plain = bytearray(len(raw) - 4)
        for i in range(len(plain)):
            plain[i] = ((raw[i + 4] + seed - MIGU_KEY[i % len(MIGU_KEY)]) & 0xFF)
        return plain.decode("utf-8", "replace")
    return raw.decode("utf-8", "replace")


def probe_migu(keyword="混账 周柏豪"):
    print("=" * 60)
    print("[1/5] migu 主源搜索(fee=1 VIP 歌的路由目标)")
    switch = urllib.parse.quote('{"song":1,"album":0,"singer":0,"tagSong":1,"mvSong":0,"bestShow":1}')
    url = ("https://c.musicapp.migu.cn/v1.0/content/search_all.do"
           f"?text={urllib.parse.quote(keyword)}&pageNo=1&pageSize=10&isCopyright=1&sort=1&searchSwitch={switch}")
    try:
        j = http_get_json(url, MIGU_HEADERS)
    except Exception as e:
        print(f"  search 失败: {e}")
        return
    results = (j.get("songResultData") or {}).get("result") or []
    if not results:
        print("  ❌ migu 搜索无结果 — 主源哑炮")
        return
    print(f"  搜索返回 {len(results)} 条:")
    target = None
    for it in results[:5]:
        name = it.get("name") or it.get("songName") or ""
        singers = ",".join(s.get("name", "") for s in (it.get("singers") or []))
        mark = ""
        if "混账" in name or "混帐" in name:
            mark = " <-- 目标"
            if target is None:
                target = it
        print(f"    - {name} | {singers}{mark}")
    if not target:
        print("  ❌ migu 搜索没有 title 含「混账/混帐」的条目 — fallback 过滤(title==keyword)不会命中,主源返回 null")
        return

    cid = target.get("contentId", "")
    copyid = target.get("copyrightId", "")
    print(f"  目标命中: contentId={cid} copyrightId={copyid}")
    print("  逐音质解析 listen-url (PQ/HQ/SQ/ZQ):")
    for fmt in ["PQ", "HQ", "SQ", "ZQ"]:
        lu = ("https://c.musicapp.migu.cn/strategy/listen-url/h5/v2.4"
              f"?contentId={cid}&copyrightId={copyid}&resourceType=2&netType=01"
              f"&toneFlag={fmt}&scene=&lowerQualityContentId={cid}")
        h = dict(MIGU_HEADERS)
        h["birth"] = "h5page"
        h["signature"] = "1"
        try:
            raw = http_get(lu, h)
            txt = migu_decrypt(raw)
            j2 = json.loads(txt)
            data = j2.get("data") or {}
            dl = data.get("url") or ""
            code = j2.get("code", "")
            if not dl:
                print(f"    [{fmt}] code={code} 无 url, data={json.dumps(data, ensure_ascii=False)[:200]}")
                continue
            size = head_length(dl)
            # 估算时长:URL 含码率标识;mp3 128kbps = 16KB/s
            bitrate_tag = ""
            for tag in ["128", "320", "flac", "FLAC", "320k", "128k"]:
                if tag in dl:
                    bitrate_tag = tag
                    break
            est = ""
            if size:
                if "flac" in dl.lower():
                    est = f"≈{size / 1000000:.0f}s(flac~1Mbps)"  # flac ~1000kbps=125KB/s
                elif "320" in bitrate_tag:
                    est = f"≈{size / 40000:.0f}s(320k)"
                else:
                    est = f"≈{size / 16000:.0f}s(128k)"
            print(f"    [{fmt}] url 命中 size={size} {bitrate_tag} {est}")
            print(f"           {dl[:150]}")
            return  # 拿到一个就够
        except Exception as e:
            print(f"    [{fmt}] 异常: {e}")


def probe_kuwo(keyword="混账 周柏豪"):
    print("=" * 60)
    print("[2/5] kuwo 搜索(fallback 第 1 环)")
    url = ("http://www.kuwo.cn/search/searchMusicBykeyWord?"
           "vipver=1&client=kt&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&mobi=1"
           f"&issubtitle=1&show_copyright_off=1&pn=0&rn=10&all={urllib.parse.quote(keyword)}")
    try:
        j = http_get_json(url)
    except Exception as e:
        print(f"  search 失败: {e}")
        return
    abslist = j.get("abslist") or []
    print(f"  搜索返回 {len(abslist)} 条:")
    hit = 0
    for it in abslist[:8]:
        name = it.get("SONGNAME") or it.get("name") or ""
        artist = it.get("ARTIST") or it.get("artist") or ""
        mark = " <-- title 含目标" if ("混账" in name or "混帐" in name) else ""
        if mark:
            hit += 1
        print(f"    - {name} | {artist}{mark}")
    if not hit:
        print("  ❌ kuwo 无 title 匹配 — resolve 环节不会命中")


def probe_qq(keyword="混账 周柏豪"):
    print("=" * 60)
    print("[3/5] qq 搜索 + vkeys 解析(fallback 第 2 环)")
    # QQ musicu.fcg 搜索(复刻 QQMusicSource)
    body = {
        "req_1": {
            "method": "DoSearchForQQMusicDesktop",
            "module": "music.search.SearchCgiService",
            "param": {"search_type": 0, "query": keyword, "page_num": 1, "num_per_page": 10},
        }
    }
    req = urllib.request.Request(
        "https://u.y.qq.com/cgi-bin/musicu.fcg",
        data=json.dumps(body).encode(),
        headers={"User-Agent": UA, "Content-Type": "application/json",
                 "Referer": "https://y.qq.com/", "Origin": "https://y.qq.com"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            j = json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:
        print(f"  search 失败: {e}")
        return
    songs = (((j.get("req_1") or {}).get("data") or {}).get("body") or {}).get("song") or {}
    lst = songs.get("list") or []
    print(f"  搜索返回 {len(lst)} 条:")
    target_mid = None
    for it in lst[:8]:
        name = it.get("name") or it.get("title") or ""
        singer = ",".join(s.get("name", "") for s in (it.get("singer") or []))
        album_mid = ((it.get("album") or {}).get("mid")) or ""
        mark = ""
        if "混账" in name or "混帐" in name:
            mark = " <-- title 含目标"
            if target_mid is None:
                target_mid = it.get("mid") or ""
        print(f"    - {name} | {singer} | album.mid={album_mid[:12]}{mark}")
    if not target_mid:
        print("  ❌ qq 无 title 匹配 — resolve 环节不会命中")
        return
    print(f"  目标 mid={target_mid},用 vkeys 解析(复刻 QQMusicSource.resolvePlayUrl):")
    for q in [999, 320, 128]:
        try:
            vj = http_get_json(f"https://api.vkeys.cn/music/tencent/song/link?mid={target_mid}&quality={q}")
            info = vj.get("data") or {}
            url_out = info.get("url") or ""
            if not url_out:
                print(f"    [q={q}] 无 url, resp={json.dumps(vj, ensure_ascii=False)[:180]}")
                continue
            size = head_length(url_out)
            print(f"    [q={q}] url 命中 size={size} {url_out[:120]}")
            return
        except Exception as e:
            print(f"    [q={q}] 异常: {e}")


def probe_joox(keyword="混账 周柏豪"):
    print("=" * 60)
    print("[4/5] joox 搜索(fallback 第 6 环,resolve 官方签名复杂只测 search)")
    url = ("https://cache.api.joox.com/openjoox/v2/search_type?"
           f"country=hk&lang=zh_TW&key={urllib.parse.quote(keyword)}&type=0")
    h = {"User-Agent": UA, "Accept": "application/json, text/plain, */*",
         "Origin": "https://www.joox.com", "Referer": "https://www.joox.com/",
         "x-forwarded-for": "36.73.34.109"}
    try:
        j = http_get_json(url, h)
    except Exception as e:
        print(f"  search 失败: {e}")
        return
    tracks = j.get("tracks") or []
    print(f"  搜索返回 {len(tracks)} 条:")
    hit = 0
    for t in tracks[:8]:
        if isinstance(t, list):
            t = t[0] if t else {}
        name = t.get("name") or t.get("title") or ""
        singer = t.get("singer") or t.get("singer_name") or ""
        mark = " <-- title 含目标" if ("混账" in name or "混帐" in name) else ""
        if mark:
            hit += 1
        print(f"    - {name} | {singer}{mark}")
    if not hit:
        print("  ❌ joox 无 title 匹配 — resolve 环节不会命中(即使 resolve 可用)")


def probe_apple(keyword="混帐 周柏豪"):
    print("=" * 60)
    print("[5/5] apple iTunes preview(fallback 最后一环,已知 30s 试听)")
    url = (f"https://itunes.apple.com/search?term={urllib.parse.quote(keyword)}"
           "&media=music&entity=song&limit=5&country=HK")
    try:
        j = http_get_json(url)
    except Exception as e:
        print(f"  search 失败: {e}")
        return
    results = j.get("results") or []
    print(f"  搜索返回 {len(results)} 条:")
    for r in results[:5]:
        preview = r.get("previewUrl") or ""
        size = head_length(preview) if preview else 0
        print(f"    - {r.get('trackName')} | {r.get('artistName')} | preview size={size}"
              f" (30s aac≈250k/s=480KB→~30s)")
    if results:
        print("  ✅ apple 必然兜底成功(30s preview) — 若前面环节全哑,最终播的就是这个 30 秒")


if __name__ == "__main__":
    print("「混账-周柏豪」fallback 链实测  (2026-09-10)")
    print()
    probe_migu()
    probe_kuwo()
    probe_qq()
    probe_joox()
    probe_apple()
    print("=" * 60)
    print("结论自动判读见报告")
