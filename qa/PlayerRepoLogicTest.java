/**
 * QA 独立验证：把 PlayerRepository 的自动续播状态机（不含 Android 依赖）
 * 原样移植到纯 JVM 上跑，验证「一首播完是否会无限自转 / 重入崩溃」。
 *
 * 移植来源（android/app/src/main/java/com/soundtrack/music/player/PlayerRepository.kt）：
 *   - L96-105   onPlaybackStateChanged(STATE_ENDED) -> mainHandler.post { guard -> next() }
 *   - L315-324  findNextIndex(skip)
 *   - L333-347  stopAtQueueEnd()
 *   - L354-365  next()
 *   - L277-283  stopPlayback()
 *   - L285-298  setMediaAndPlay()
 *
 * 运行：java PlayerRepoLogicTest.java
 */
import java.util.*;

public class PlayerRepoLogicTest {

    // ===== 模拟 ExoPlayer 的状态常量 =====
    static final int STATE_IDLE = 1, STATE_BUFFERING = 2, STATE_READY = 3, STATE_ENDED = 4;

    /** 模拟 ExoPlayer（只保留与续播相关的字段） */
    static class FakePlayer {
        int playbackState = STATE_IDLE;
        boolean playWhenReady = false;
        long positionMs = 0;
        long durationMs = 0;
        boolean released = false;

        void stop() { check(); playbackState = STATE_IDLE; positionMs = 0; playWhenReady = false; }
        void clearMediaItems() { check(); }
        void setMediaItem(String url) { check(); durationMs = 30_000; }
        void prepare() { check(); playbackState = STATE_BUFFERING; }
        void play() { check(); playWhenReady = true; if (playbackState == STATE_BUFFERING) playbackState = STATE_READY; }
        void pause() { check(); playWhenReady = false; if (playbackState == STATE_ENDED) playbackState = STATE_READY; }
        void seekTo(long ms) { check(); positionMs = ms; if (playbackState == STATE_ENDED) playbackState = STATE_READY; }
        boolean isPlaying() { return playWhenReady && playbackState == STATE_READY; }
        private void check() {
            if (released) throw new IllegalStateException("Player is released");
        }
    }

    /** 模拟主线程消息队列（Handler(Looper.getMainLooper()).post） */
    static class MainLooper {
        final Deque<Runnable> queue = new ArrayDeque<>();
        void post(Runnable r) { queue.addLast(r); }
        /** 把队列排空；返回本轮执行的消息条数，用于检测「无限自转」 */
        int drain(int maxMessages) {
            int n = 0;
            while (!queue.isEmpty()) {
                if (++n > maxMessages) return n;   // 超过上限 => 判定为死循环
                queue.pollFirst().run();
            }
            return n;
        }
    }

    /**
     * PlayerRepository 续播逻辑的等价移植。
     * 队列元素 hasUrl=false 表示需要先解析；resolveFail 集合模拟解析失败的音源。
     */
    static class Repo {
        final FakePlayer player = new FakePlayer();
        final MainLooper main = new MainLooper();
        final List<String> queue = new ArrayList<>();
        final Set<Integer> failedInThisRound = new LinkedHashSet<>();
        final Set<Integer> playedInThisRound = new LinkedHashSet<>();
        int currentIndex = -1;
        /** 每首歌是否需要解析；null = 解析失败 */
        final Map<Integer, Boolean> resolveResult = new HashMap<>();
        int playAtCalls = 0;
        int stopAtQueueEndCalls = 0;

        // --- L96-105 原样 ---
        void onPlaybackStateChanged(int state) {
            if (state == STATE_ENDED) {
                main.post(() -> {
                    int now = safePlaybackState();
                    if (now == STATE_ENDED) next();
                });
            }
        }
        int safePlaybackState() {
            try { return player.playbackState; } catch (Throwable t) { return STATE_IDLE; }
        }

        // --- L354-365 原样 ---
        void next() {
            if (queue.isEmpty()) return;
            if (currentIndex >= 0 && currentIndex < queue.size()) playedInThisRound.add(currentIndex);
            Set<Integer> skip = new HashSet<>(failedInThisRound);
            skip.addAll(playedInThisRound);
            int nextIdx = findNextIndex(skip);
            if (nextIdx < 0) { stopAtQueueEnd(); return; }
            playAt(nextIdx);
        }

        // --- L315-324 原样 ---
        int findNextIndex(Set<Integer> skip) {
            if (queue.isEmpty()) return -1;
            int idx = currentIndex;
            for (int i = 0; i < queue.size(); i++) {
                idx = (idx + 1) % queue.size();
                if (skip.contains(idx)) continue;
                return idx;
            }
            return -1;
        }

        // --- L333-347 原样 ---
        void stopAtQueueEnd() {
            stopAtQueueEndCalls++;
            failedInThisRound.clear();
            playedInThisRound.clear();
            boolean ok;
            try { player.pause(); player.seekTo(0L); ok = true; } catch (Throwable t) { ok = false; }
            if (!ok) stopPlayback();
        }

        // --- L277-283 原样 ---
        void stopPlayback() {
            try { player.stop(); player.clearMediaItems(); } catch (Throwable ignored) {}
        }

        // --- L285-298 + L176-219 简化（同步完成解析以观测最终态） ---
        void playAt(int index) {
            playAtCalls++;
            if (index < 0 || index >= queue.size()) return;
            currentIndex = index;
            Boolean resolvable = resolveResult.getOrDefault(index, true);
            if (resolvable == null) {           // 解析失败分支 L211-216
                failedInThisRound.add(index);
                stopPlayback();
                return;
            }
            try {                                // setMediaAndPlay L286-293
                player.stop();
                player.clearMediaItems();
                player.setMediaItem("http://x/" + index);
                player.prepare();
                player.play();
            } catch (Throwable ignored) {}
        }

        /** 模拟"当前这首播完"：位置推进到末尾且 playWhenReady -> ENDED */
        void simulateNaturalEnd() {
            if (!player.playWhenReady || player.playbackState != STATE_READY) return;
            player.positionMs = player.durationMs;
            player.playbackState = STATE_ENDED;
            onPlaybackStateChanged(STATE_ENDED);
        }
    }

    // ================= 测试用例 =================
    static int passed = 0, failed = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) { passed++; System.out.println("  [PASS] " + name); }
        else { failed++; System.out.println("  [FAIL] " + name + "  ->  " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== PlayerRepository 自动续播逻辑 JVM 验证 ===");

        // --- 用例 1：单曲队列（首页「新歌速递」点一首）播完 ---
        System.out.println("\n[用例1] 单曲队列播完应停下并暂停，不自转、不崩");
        {
            Repo r = new Repo();
            r.resolveResult.put(0, true);
            r.queue.add("song0");
            r.playAt(0);
            check("起播后处于播放态", r.player.isPlaying(), "state=" + r.player.playbackState);
            r.simulateNaturalEnd();
            int msgs = r.main.drain(50);
            check("消息数未失控(无死循环)", msgs <= 2, "msgs=" + msgs);
            check("已触发 stopAtQueueEnd", r.stopAtQueueEndCalls == 1, "calls=" + r.stopAtQueueEndCalls);
            check("最终 playWhenReady=false（暂停）", !r.player.playWhenReady, "pwr=true");
            check("最终 position 回到 0", r.player.positionMs == 0, "pos=" + r.player.positionMs);
            check("最终状态不是 STATE_ENDED", r.player.playbackState != STATE_ENDED, "state=ENDED");
            check("没有再次调用 playAt 自转", r.playAtCalls == 1, "playAtCalls=" + r.playAtCalls);
        }

        // --- 用例 2：3 首队列依次播完，最后停下 ---
        System.out.println("\n[用例2] 3 首队列播完 3 首后停下");
        {
            Repo r = new Repo();
            for (int i = 0; i < 3; i++) { r.queue.add("song" + i); r.resolveResult.put(i, true); }
            r.playAt(0);
            int ends = 0;
            for (int k = 0; k < 3; k++) { r.simulateNaturalEnd(); r.main.drain(50); ends++; }
            check("依次播了 3 首", r.playAtCalls == 3, "playAtCalls=" + r.playAtCalls);
            check("第 3 首播完后停下", r.stopAtQueueEndCalls == 1, "stopCalls=" + r.stopAtQueueEndCalls);
            check("停在暂停态", !r.player.playWhenReady, "pwr=true");
        }

        // --- 用例 3：所有候选都解析失败，必须真正停止，不能无限 playAt ---
        System.out.println("\n[用例3] 全部解析失败时应熔断，不无限轮询");
        {
            Repo r = new Repo();
            for (int i = 0; i < 5; i++) { r.queue.add("song" + i); r.resolveResult.put(i, null); }
            r.playAt(0);                       // 解析失败 -> stopPlayback
            r.playAt(1); r.playAt(2); r.playAt(3); r.playAt(4);
            check("5 首都记入 failedInThisRound", r.failedInThisRound.size() == 5, "size=" + r.failedInThisRound.size());
            int nextIdx = r.findNextIndex(new HashSet<>(r.failedInThisRound));
            check("findNextIndex 全失败时返回 -1（熔断生效）", nextIdx == -1, "nextIdx=" + nextIdx);
            r.currentIndex = 4;
            r.playedInThisRound.add(4);
            r.next();
            check("next() 走 stopAtQueueEnd 而非再 playAt", r.stopAtQueueEndCalls == 1 && r.playAtCalls == 5,
                    "stopCalls=" + r.stopAtQueueEndCalls + " playAtCalls=" + r.playAtCalls);
        }

        // --- 用例 4：ENDED 后用户手动 stop，排队中的 next() 必须失效 ---
        System.out.println("\n[用例4] ENDED 后插进 stop()，排队中的 next() 不应把歌又播起来");
        {
            Repo r = new Repo();
            for (int i = 0; i < 3; i++) { r.queue.add("song" + i); r.resolveResult.put(i, true); }
            r.playAt(0);
            r.simulateNaturalEnd();            // 此时队列里已有一个待执行的 next()
            r.stopPlayback();                  // 用户操作：清空/停止
            r.main.drain(50);                  // 队列里的 next() 现在才跑
            check("守卫生效：state=IDLE 时 next() 不执行", r.playAtCalls == 1, "playAtCalls=" + r.playAtCalls);
            check("仍处于停止态", !r.player.playWhenReady, "pwr=true");
        }

        // --- 用例 5：stopAtQueueEnd 后再点播放，能正常重播，且不会立刻再次 ENDED ---
        System.out.println("\n[用例5] 停下后手动重播可恢复，且不会立刻再次 ENDED");
        {
            Repo r = new Repo();
            r.queue.add("song0");
            r.resolveResult.put(0, true);
            r.playAt(0);
            r.simulateNaturalEnd();
            r.main.drain(50);
            r.player.play();                   // 用户点播放
            check("重播恢复播放态", r.player.isPlaying(), "state=" + r.player.playbackState);
            check("重播位置归零", r.player.positionMs == 0, "pos=" + r.player.positionMs);
            r.simulateNaturalEnd();
            r.main.drain(50);
            check("再次播完仍安全停下", r.stopAtQueueEndCalls == 2 && !r.player.playWhenReady,
                    "stopCalls=" + r.stopAtQueueEndCalls);
        }

        // --- 用例 6：空队列不炸 ---
        System.out.println("\n[用例6] 空队列调用 next() 不应越界");
        {
            Repo r = new Repo();
            r.next();
            check("空队列 next() 安全返回", r.playAtCalls == 0 && r.stopAtQueueEndCalls == 0, "不该有调用");
        }

        System.out.println("\n========================================");
        System.out.println("总计: " + (passed + failed) + " | 通过: " + passed + " | 失败: " + failed);
        System.out.println(failed == 0 ? "结果: 全部通过（续播逻辑无死循环 / 无重入）" : "结果: 存在失败项");
        if (failed > 0) System.exit(1);
    }
}
