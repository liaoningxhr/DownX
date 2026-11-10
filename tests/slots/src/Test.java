package com.downx.app;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发名额（Slots）的桌面压测：
 * 这块逻辑一旦漏放名额，手机上表现为「永远提示还有一条在下载」，无法自查，
 * 所以在主机上把正常路径、异常路径、并发竞争全跑一遍。
 */
public class Test {

    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  → " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        // ① 图片上限
        Slots s = new Slots(3);
        int a1 = s.admit(true), a2 = s.admit(true), a3 = s.admit(true), a4 = s.admit(true);
        check("图片前三条放行", a1 == 0 && a2 == 1 && a3 == 2, a1 + "," + a2 + "," + a3);
        check("第四条被拒", a4 == -1, "got " + a4);
        check("此时在跑 3 条", s.active() == 3 && s.photos() == 3, s.active() + "/" + s.photos());

        // ② 视频独占
        Slots v = new Slots(3);
        check("空的时候视频能进", v.admit(false) == 0, "");
        check("视频在跑时图片被拒", v.admit(true) == -1, "");
        check("视频在跑时第二个视频被拒", v.admit(false) == -1, "");
        v.release(false);
        check("视频放掉后图片能进", v.admit(true) == 0, "");

        // ③ release 之后名额确实回来了
        Slots r = new Slots(3);
        r.admit(true);
        r.admit(true);
        r.admit(true);
        check("满额时被拒", r.admit(true) == -1, "");
        r.release(true);
        check("放掉一条又能进", r.admit(true) == 2, "");
        check("总数没有超过上限", r.active() == 3, "active=" + r.active());

        // ④ 异常路径多放几次，不许把计数打成负数/错位
        Slots d = new Slots(3);
        d.admit(true);
        d.release(true);
        d.release(true);
        d.release(true);
        check("多放不会变成负数", d.active() == 0 && d.photos() == 0,
                d.active() + "/" + d.photos());

        // ⑤ 随机压测：每一步都检查不变量，最后必须回到 0
        Slots rs = new Slots(3);
        Random rnd = new Random(20261110);
        // 0=没持有 1=持有图片 2=持有视频：释放时必须用当初申请的类型，
        // 否则测的就不是真实用法（DlService 里两处用的是同一个 kind）
        int[] held = new int[64];
        String bad = null;
        for (int i = 0; i < 200000 && bad == null; i++) {
            int k = rnd.nextInt(held.length);
            boolean photo = rnd.nextInt(4) != 0;      // 3/4 是图片
            if (held[k] == 0) {
                if (rs.admit(photo) >= 0) held[k] = photo ? 1 : 2;
            } else {
                rs.release(held[k] == 1);
                held[k] = 0;
            }
            if (rs.active() < 0 || rs.active() > 3) bad = "i=" + i + " active=" + rs.active();
            if (rs.photos() < 0 || rs.photos() > rs.active()) bad = "i=" + i + " photos=" + rs.photos();
        }
        check("20 万次随机操作内不变量始终成立", bad == null, String.valueOf(bad));
        for (int i = 0; i < held.length; i++) if (held[i] != 0) rs.release(held[i] == 1);
        check("全部放掉后归零", rs.active() == 0 && rs.photos() == 0, rs.active() + "/" + rs.photos());

        // ⑥ 并发压测：8 线程抢名额，绝不能超过 3 条同时拿到
        final Slots cs = new Slots(3);
        final AtomicInteger now = new AtomicInteger();
        final AtomicInteger maxSeen = new AtomicInteger();
        final AtomicInteger got = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) { }
                Random rr = new Random();
                for (int n = 0; n < 3000; n++) {
                    if (cs.admit(true) >= 0) {
                        got.incrementAndGet();
                        int c = now.incrementAndGet();
                        maxSeen.accumulateAndGet(c, Math::max);
                        now.decrementAndGet();
                        cs.release(true);
                    }
                }
            });
            ts[i].start();
        }
        start.countDown();
        for (Thread t : ts) t.join();
        check("并发下同时在跑不超过 3", maxSeen.get() <= 3, "max=" + maxSeen.get());
        check("并发结束后归零", cs.active() == 0, "active=" + cs.active());
        check("并发确实抢到了名额", got.get() > 1000, "got=" + got.get());

        System.out.println("\n结果: " + pass + " PASS, " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }
}
