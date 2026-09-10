(function () {
  'use strict';

  // ---------------------------------------------------------------------------
  // 配置与状态
  // ---------------------------------------------------------------------------
  // 音源清单：启动时从服务端 /api/sources 动态加载（全部 58 源，分组渲染）
  let SOURCES = [
    { id: 'migu', label: '咪咕音乐', group: 'core', default: true },
  ];

  const state = {
    activeSources: new Set(),
    results: [],
    sourceStatus: {},
    queue: [],
    currentIndex: -1,
    isPlaying: false,
    currentTime: 0,
    duration: 0,
    volume: 0.8,
    lyricLines: [],
    lyricPanelOpen: localStorage.getItem('lyricPanelOpen') === 'true',
    history: JSON.parse(localStorage.getItem('searchHistory') || '[]'),
    currentKeyword: '',
    currentPlayingToken: null,
  };

  const audio = document.getElementById('audio');
  const resultsList = document.getElementById('resultsList');
  const sourceBar = document.getElementById('sourceBar');
  const searchInput = document.getElementById('searchInput');
  const emptyState = document.getElementById('emptyState');
  const lyricDrawer = document.getElementById('lyricDrawer');
  const lyricBody = document.getElementById('lyricBody');
  const wave = document.getElementById('wave');

  // ---------------------------------------------------------------------------
  // 工具函数
  // ---------------------------------------------------------------------------
  function $(id) { return document.getElementById(id); }

  function formatTime(sec) {
    if (!isFinite(sec) || sec < 0) return '0:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  function safeName(name) {
    return String(name || '').replace(/[\\/:*?"<>|]/g, '_').trim() || 'track';
  }

  function showToast(msg) {
    let el = document.querySelector('.toast');
    if (!el) {
      el = document.createElement('div');
      el.className = 'toast';
      document.body.appendChild(el);
    }
    el.textContent = msg;
    el.classList.add('show');
    setTimeout(() => el.classList.remove('show'), 2200);
  }

  function saveHistory() {
    localStorage.setItem('searchHistory', JSON.stringify(state.history.slice(0, 10)));
  }

  function addHistory(keyword) {
    if (!keyword) return;
    state.history = state.history.filter(k => k !== keyword);
    state.history.unshift(keyword);
    state.history = state.history.slice(0, 10);
    saveHistory();
    renderHistory();
  }

  // ---------------------------------------------------------------------------
  // 音源选择
  // ---------------------------------------------------------------------------
  function renderSources() {
    let lastGroup = null;
    sourceBar.innerHTML = SOURCES.map(s => {
      const active = state.activeSources.has(s.id);
      const status = state.sourceStatus[s.id];
      let statusText = '';
      if (status === 'loading') statusText = '●';
      else if (status === 'done') statusText = '';
      else if (status === 'error') statusText = '✕';
      const divider = lastGroup && lastGroup !== s.group ? '<span class="source-divider"></span>' : '';
      lastGroup = s.group;
      const groupTitle = { core: '', cn: '', radio: '', overseas: '' };
      void groupTitle;
      return `${divider}<div class="source-pill ${active ? 'active' : ''}" data-id="${s.id}" title="${s.label}">
          <span class="dot"></span>
          <span>${s.label}</span>
          ${statusText ? `<span class="status">${statusText}</span>` : ''}
        </div>`;
    }).join('') + '<div class="source-hint">点按音源可开 / 关，再次搜索即生效</div>';

    sourceBar.querySelectorAll('.source-pill').forEach(el => {
      el.addEventListener('click', () => {
        const id = el.dataset.id;
        if (state.activeSources.has(id)) {
          state.activeSources.delete(id);
        } else {
          state.activeSources.add(id);
        }
        renderSources();
      });
    });
  }

  async function loadSources() {
    try {
      const resp = await fetch('/api/sources');
      const data = await resp.json();
      if (Array.isArray(data.sources) && data.sources.length) {
        SOURCES = data.sources;
      }
    } catch (err) { /* 服务端不可达时保留兜底列表 */ }
    SOURCES.filter(s => s.default).forEach(s => state.activeSources.add(s.id));
    renderSources();
  }

  // ---------------------------------------------------------------------------
  // 搜索
  // ---------------------------------------------------------------------------
  let currentEventSource = null;

  function doSearch(keyword, appendHistory = true) {
    keyword = keyword.trim();
    if (!keyword) return;
    state.currentKeyword = keyword;
    if (appendHistory) addHistory(keyword);

    state.results = [];
    state.queue = [];
    state.currentIndex = -1;
    state.sourceStatus = {};
    resultsList.innerHTML = '';
    emptyState.textContent = '搜索中…';
    emptyState.style.display = 'block';

    if (currentEventSource) {
      currentEventSource.close();
      currentEventSource = null;
    }

    const qs = new URLSearchParams({ q: keyword, sources: Array.from(state.activeSources).join(',') });
    const es = new EventSource(`/api/search?${qs.toString()}`);
    currentEventSource = es;

    es.addEventListener('source_start', e => {
      const data = JSON.parse(e.data);
      state.sourceStatus[data.source] = 'loading';
      renderSources();
    });

    es.addEventListener('result', e => {
      const data = JSON.parse(e.data);
      state.results.push(data);
      state.queue.push(data);
      appendResultRow(data);
      emptyState.style.display = 'none';
    });

    es.addEventListener('source_done', e => {
      const data = JSON.parse(e.data);
      state.sourceStatus[data.source] = 'done';
      renderSources();
    });

    es.addEventListener('source_error', e => {
      const data = JSON.parse(e.data);
      state.sourceStatus[data.source] = 'error';
      showToast(`${SOURCES.find(s => s.id === data.source)?.label || data.source}: 无响应`);
      renderSources();
    });

    es.addEventListener('done', () => {
      emptyState.style.display = state.results.length ? 'none' : 'block';
      emptyState.textContent = state.results.length ? '' : '未找到结果';
      es.close();
      currentEventSource = null;
    });

    es.onerror = () => {
      emptyState.textContent = '搜索连接出错';
      es.close();
      currentEventSource = null;
    };
  }

  // ---------------------------------------------------------------------------
  // 结果列表渲染
  // ---------------------------------------------------------------------------
  function appendResultRow(track) {
    const el = document.createElement('div');
    el.className = 'track-row';
    el.dataset.token = track.token;
    el.innerHTML = `
      <div class="track-main">
        <div class="track-cover-wrap">
          <img class="track-cover" src="${track.cover_url || ''}" alt="" loading="lazy" onerror="this.style.display='none'">
          <div class="track-play">▶</div>
        </div>
        <div class="track-info">
          <div class="track-name">${escapeHtml(track.song_name)}</div>
          <div class="track-artist">${escapeHtml(track.singers)}</div>
        </div>
      </div>
      <div class="track-album" title="${escapeHtml(track.album)}">${escapeHtml(track.album)}</div>
      <div class="track-right">
        <div class="track-badges">
          ${track.has_lyric ? '<span class="badge lyric">词</span>' : ''}
          ${track.lossless ? '<span class="badge lossless">无损</span>' : (track.bitrate >= 320 ? '<span class="badge hq">高品质</span>' : '<span class="badge std">标准</span>')}
          <span class="badge source">${escapeHtml(track.source_label || track.source)}</span>
        </div>
        <span class="track-duration">${track.duration || '0:00'}</span>
        <div class="track-actions">
          <button title="下载" data-action="download">⬇</button>
        </div>
      </div>
    `;

    el.addEventListener('dblclick', () => playByToken(track.token));
    el.querySelector('.track-play').addEventListener('click', e => {
      e.stopPropagation();
      playByToken(track.token);
    });
    el.querySelector('[data-action="download"]').addEventListener('click', e => {
      e.stopPropagation();
      downloadTrack(track);
    });

    resultsList.appendChild(el);
  }

  function escapeHtml(s) {
    const div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
  }

  function updatePlayingRow() {
    document.querySelectorAll('.track-row').forEach(el => {
      el.classList.toggle('playing', el.dataset.token === state.currentPlayingToken);
    });
  }

  // ---------------------------------------------------------------------------
  // 播放器
  // ---------------------------------------------------------------------------
  function playByToken(token) {
    const idx = state.queue.findIndex(t => t.token === token);
    if (idx < 0) return;
    playAt(idx);
  }

  function playAt(index) {
    if (index < 0 || index >= state.queue.length) return;
    state.currentIndex = index;
    const track = state.queue[index];
    state.currentPlayingToken = track.token;

    audio.src = `/api/stream?id=${track.token}`;
    audio.volume = state.volume;
    audio.play().catch(err => {
      // 关键：失败时不要自动 nextTrack()。
      // 之前失败即切下一首，而下一首往往同样失效，
      // 形成"逐首请求 /api/stream"的雪崩，最终把浏览器卡死。
      showToast('播放失败：' + (err.message || '音源不可用'));
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
      state.isPlaying = false;
      updatePlayButton();
    });

    $('playerTitle').textContent = track.song_name;
    $('playerArtist').textContent = track.singers;
    $('playerCover').src = track.cover_url || '';
    $('playerCover').onerror = () => { $('playerCover').src = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'; };

    loadLyrics(track.token);
    updatePlayingRow();
    updatePlayButton();
  }

  function togglePlay() {
    if (!audio.src) return;
    if (audio.paused) audio.play().catch(() => {});
    else audio.pause();
  }

  function nextTrack() {
    if (!state.queue.length) return;
    const start = state.currentIndex;
    let idx = state.currentIndex;
    for (let i = 0; i < state.queue.length; i++) {
      idx = (idx + 1) % state.queue.length;
      if (idx !== start) {
        playAt(idx);
        return;
      }
    }
  }

  function prevTrack() {
    if (!state.queue.length) return;
    let idx = state.currentIndex - 1;
    if (idx < 0) idx = state.queue.length - 1;
    playAt(idx);
  }

  function updatePlayButton() {
    $('btnPlay').textContent = audio.paused ? '▶' : '⏸';
    wave.classList.toggle('paused', audio.paused);
  }

  // ---------------------------------------------------------------------------
  // 进度条与音量
  // ---------------------------------------------------------------------------
  function setupProgress() {
    const wrap = $('progressWrap');
    const fill = $('progressFill');
    const thumb = $('progressThumb');
    let dragging = false;

    function setByEvent(e) {
      const rect = wrap.getBoundingClientRect();
      const x = Math.max(0, Math.min(e.clientX - rect.left, rect.width));
      const ratio = x / rect.width;
      if (audio.duration) audio.currentTime = ratio * audio.duration;
      updateProgressUI(ratio);
    }

    wrap.addEventListener('mousedown', e => {
      dragging = true;
      setByEvent(e);
    });
    window.addEventListener('mousemove', e => { if (dragging) setByEvent(e); });
    window.addEventListener('mouseup', () => { dragging = false; });

    audio.addEventListener('timeupdate', () => {
      if (!dragging) updateProgressUI(audio.currentTime / (audio.duration || 1));
      state.currentTime = audio.currentTime;
      $('timeCurrent').textContent = formatTime(audio.currentTime);
      syncLyric(audio.currentTime);
    });

    audio.addEventListener('loadedmetadata', () => {
      state.duration = audio.duration || 0;
      $('timeTotal').textContent = formatTime(state.duration);
    });

    audio.addEventListener('play', () => { state.isPlaying = true; updatePlayButton(); });
    audio.addEventListener('pause', () => { state.isPlaying = false; updatePlayButton(); });
    audio.addEventListener('ended', () => nextTrack());
    audio.addEventListener('error', () => {
      // 关键：不要自动 nextTrack()！之前失败即切下一首，
      // 而下一首往往同样失效，形成雪崩式请求把浏览器卡死。
      // 现在停在当前歌 + 提示用户，不再切下一首。
      showToast('音频加载失败，请尝试切到其他音源或换一首');
      state.isPlaying = false;
      updatePlayButton();
    });
  }

  function updateProgressUI(ratio) {
    const pct = Math.max(0, Math.min(ratio * 100, 100));
    $('progressFill').style.width = `${pct}%`;
    $('progressThumb').style.left = `${pct}%`;
  }

  function setupVolume() {
    const slider = $('volumeSlider');
    const fill = $('volumeFill');
    audio.volume = state.volume;
    fill.style.width = `${state.volume * 100}%`;

    slider.addEventListener('mousedown', e => {
      const move = ev => {
        const rect = slider.getBoundingClientRect();
        const ratio = Math.max(0, Math.min((ev.clientX - rect.left) / rect.width, 1));
        state.volume = ratio;
        audio.volume = ratio;
        fill.style.width = `${ratio * 100}%`;
      };
      move(e);
      const up = () => { window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up); };
      window.addEventListener('mousemove', move);
      window.addEventListener('mouseup', up);
    });
  }

  // ---------------------------------------------------------------------------
  // 歌词
  // ---------------------------------------------------------------------------
  async function loadLyrics(token) {
    state.lyricLines = [];
    lyricBody.innerHTML = '<div class="lyric-empty">加载中…</div>';
    try {
      const resp = await fetch(`/api/lyric?id=${token}`);
      if (!resp.ok) throw new Error('no lyric');
      const text = await resp.text();
      state.lyricLines = parseLrc(text);
      renderLyrics();
    } catch {
      state.lyricLines = [];
      lyricBody.innerHTML = '<div class="lyric-empty">暂无歌词</div>';
    }
  }

  function parseLrc(raw) {
    const lines = [];
    const regex = /\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\](.*)/g;
    let m;
    while ((m = regex.exec(raw || '')) !== null) {
      const min = parseInt(m[1], 10);
      const sec = parseInt(m[2], 10);
      const ms = parseInt((m[3] || '0').padEnd(3, '0'), 10);
      const time = min * 60 + sec + ms / 1000;
      const text = m[4].trim();
      lines.push({ time, text });
    }
    lines.sort((a, b) => a.time - b.time);
    if (!lines.length && (raw || '').trim()) {
      return raw.trim().split('\n').map(line => ({ time: -1, text: line.trim() }));
    }
    return lines;
  }

  function renderLyrics() {
    if (!state.lyricLines.length) {
      lyricBody.innerHTML = '<div class="lyric-empty">暂无歌词</div>';
      return;
    }
    lyricBody.innerHTML = state.lyricLines.map((line, i) =>
      `<div class="lyric-line" data-index="${i}" data-time="${line.time}">${escapeHtml(line.text) || '·'}</div>`
    ).join('');
    lyricBody.querySelectorAll('.lyric-line').forEach(el => {
      el.addEventListener('click', () => {
        const t = parseFloat(el.dataset.time);
        if (t >= 0) audio.currentTime = t;
      });
    });
  }

  function syncLyric(currentTime) {
    if (!state.lyricLines.length) return;
    let idx = -1;
    for (let i = 0; i < state.lyricLines.length; i++) {
      if (state.lyricLines[i].time <= currentTime) idx = i;
      else break;
    }
    if (idx < 0) return;
    const lines = lyricBody.querySelectorAll('.lyric-line');
    lines.forEach((el, i) => el.classList.toggle('active', i === idx));
    const active = lines[idx];
    if (active) {
      const top = active.offsetTop - lyricBody.clientHeight / 2 + active.clientHeight / 2;
      lyricBody.scrollTo({ top, behavior: 'smooth' });
    }
  }

  function toggleLyricPanel() {
    state.lyricPanelOpen = !state.lyricPanelOpen;
    lyricDrawer.classList.toggle('open', state.lyricPanelOpen);
    $('playerLyricBtn').classList.toggle('active', state.lyricPanelOpen);
    localStorage.setItem('lyricPanelOpen', state.lyricPanelOpen);
  }

  // ---------------------------------------------------------------------------
  // 下载
  // ---------------------------------------------------------------------------
  function downloadTrack(track) {
    const title = `${safeName(track.song_name)} - ${safeName(track.singers)}`;
    const url = `/api/download?id=${track.token}&title=${encodeURIComponent(title)}`;
    const a = document.createElement('a');
    a.href = url;
    a.download = '';
    document.body.appendChild(a);
    a.click();
    a.remove();
    showToast('开始下载');
  }

  // ---------------------------------------------------------------------------
  // 搜索历史
  // ---------------------------------------------------------------------------
  const historyBox = $('searchHistory');

  function renderHistory() {
    if (!state.history.length) {
      historyBox.innerHTML = '';
      return;
    }
    historyBox.innerHTML = state.history.map(k => `
      <div class="search-history-item" data-kw="${escapeHtml(k)}">
        <span>${escapeHtml(k)}</span>
        <span class="remove" data-kw="${escapeHtml(k)}">删除</span>
      </div>
    `).join('');

    historyBox.querySelectorAll('.search-history-item').forEach(el => {
      el.addEventListener('click', e => {
        if (e.target.classList.contains('remove')) return;
        const kw = el.dataset.kw;
        searchInput.value = kw;
        doSearch(kw);
        historyBox.classList.remove('active');
      });
    });
    historyBox.querySelectorAll('.remove').forEach(el => {
      el.addEventListener('click', e => {
        e.stopPropagation();
        state.history = state.history.filter(k => k !== el.dataset.kw);
        saveHistory();
        renderHistory();
      });
    });
  }

  function setupSearchHistory() {
    renderHistory();
    searchInput.addEventListener('focus', () => {
      if (state.history.length) historyBox.classList.add('active');
    });
    searchInput.addEventListener('blur', () => {
      setTimeout(() => historyBox.classList.remove('active'), 200);
    });
  }

  // ---------------------------------------------------------------------------
  // 键盘快捷键
  // ---------------------------------------------------------------------------
  window.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT') return;
    if (e.code === 'Space') {
      e.preventDefault();
      togglePlay();
    } else if (e.code === 'ArrowLeft') {
      prevTrack();
    } else if (e.code === 'ArrowRight') {
      nextTrack();
    } else if (e.code === 'ArrowUp') {
      state.volume = Math.min(1, state.volume + 0.05);
      audio.volume = state.volume;
      $('volumeFill').style.width = `${state.volume * 100}%`;
    } else if (e.code === 'ArrowDown') {
      state.volume = Math.max(0, state.volume - 0.05);
      audio.volume = state.volume;
      $('volumeFill').style.width = `${state.volume * 100}%`;
    }
  });

  // ---------------------------------------------------------------------------
  // 事件绑定与初始化
  // ---------------------------------------------------------------------------
  searchInput.addEventListener('keydown', e => {
    if (e.key === 'Enter') {
      doSearch(searchInput.value);
      historyBox.classList.remove('active');
    }
  });

  $('btnPlay').addEventListener('click', togglePlay);
  $('btnNext').addEventListener('click', nextTrack);
  $('btnPrev').addEventListener('click', prevTrack);
  $('lyricClose').addEventListener('click', toggleLyricPanel);
  $('playerLyricBtn').addEventListener('click', toggleLyricPanel);

  setupProgress();
  setupVolume();
  setupSearchHistory();
  loadSources();

  if (state.lyricPanelOpen) {
    lyricDrawer.classList.add('open');
    $('playerLyricBtn').classList.add('active');
  }

  // 默认展开一次空列表提示
  emptyState.style.display = 'block';
})();
