"""Replica of PopsEngine.process (same constants and order) on realistic 10 Hz rpm/speed traces."""
RISE_RATE, STOP_ON_RISE, BRAKE_RATE_MIN, BRAKE_SAMPLES, REARM = 150, 400, 150, 3, 3
SHIFT_RATE, SHIFT_DROP, SHIFT_WIN, SHIFT_MIN, SHIFT_CD = 2500, 400, 350, 3000, 800
ACCEL_LB, ACCEL_RATE = 600, 250
JUMP_RATE, JUMP_MIN, JUMP_WIN, DOWN_MIN, DOWN_WAIT, JUMP_SKIP = 1500, 400, 400, 3000, 1200, 4
MIN_SPEED, BURST_MIN_RPM, BURST_STOP_RPM, STALE = 10, 2500, 2200, 600
STAND_RPM, STAND_SAMPLES, STAND_MIN_NOW = 3000, 2, 1800
THRESH = 4000


class Engine:
    def __init__(s):
        s.h = []; s.rising = s.falling = s.fall_start = 0; s.armed = True
        s.down_at = 0; s.burst_until = 0; s.last_shift = -9999; s.events = []
        s.free = 0; s.free_start = 0

    def win(s, now, ms, skip=0):
        out = []
        for t, r in reversed(s.h[:len(s.h) - skip] if skip else s.h):
            if now - t > ms: break
            out.append((t, r))
        return out

    def avg_rate(s, now, ms, skip):
        w = s.win(now, ms + 100 * skip, skip=skip)
        if len(w) < 3: return 0
        (lt, lr), (ft, fr) = w[0], w[-1]
        return (lr - fr) * 1000 / (lt - ft)

    def stop_burst(s, now, why):
        if now < s.burst_until:
            s.burst_until = now; s.events.append((now, f"   stop burst: {why}"))

    def process(s, rpm, speed, reverse, now):
        if reverse:                                   # never anything in R
            s.stop_burst(now, "reverse"); s.h.clear(); s.rising = s.falling = 0; s.down_at = 0
            return
        prev = s.h[-1] if s.h else None
        s.h.append((now, rpm))
        if not prev or now - prev[0] > STALE or rpm <= 0:
            s.rising = s.falling = 0; return
        pt, pr = prev
        rate = (rpm - pr) * 1000 / (now - pt)
        s.rising = s.rising + 1 if rate > RISE_RATE else 0
        if -SHIFT_RATE < rate < -BRAKE_RATE_MIN:
            if s.falling == 0: s.fall_start = pr
            s.falling += 1
        elif rate >= -BRAKE_RATE_MIN:
            s.falling = 0
        if rate < -BRAKE_RATE_MIN:
            if s.free == 0: s.free_start = pr
            s.free += 1
        else:
            s.free = 0
        if s.rising >= 1 and rate > STOP_ON_RISE: s.stop_burst(now, "accelerating")
        moving = speed >= MIN_SPEED
        if moving and rpm < BURST_STOP_RPM: s.stop_burst(now, f"rpm {rpm}")
        if s.rising >= REARM: s.armed = True
        if not moving:
            # Standing (P/N): a rev above 3000 and letting go -> pops, like a real car. No shifts here.
            s.down_at = 0
            if s.armed and s.free >= STAND_SAMPLES and s.free_start >= STAND_RPM and rpm >= STAND_MIN_NOW and now >= s.burst_until:
                s.armed = False; s.burst_until = now + 1200
                s.events.append((now, f"POPS standing rev from {s.free_start}"))
            return

        was_acc = s.avg_rate(now, ACCEL_LB, 1) > ACCEL_RATE
        drop = max(r for _, r in s.win(now, SHIFT_WIN)) - rpm
        if moving and rate < -SHIFT_RATE and drop >= SHIFT_DROP and pr >= SHIFT_MIN and was_acc and now - s.last_shift > SHIFT_CD:
            s.last_shift = now; s.falling = 0; s.down_at = 0
            s.events.append((now, f"UPSHIFT fart from {pr}")); return
        jump = rpm - min(r for _, r in s.win(now, JUMP_WIN))
        if moving and rate > JUMP_RATE and jump >= JUMP_MIN and rpm >= DOWN_MIN and s.down_at == 0 and s.avg_rate(now, ACCEL_LB, JUMP_SKIP) <= ACCEL_RATE:
            s.down_at = now; s.falling = 0
        if s.down_at:
            if s.falling >= BRAKE_SAMPLES and moving and rpm >= BURST_MIN_RPM:
                s.down_at = 0; s.armed = False; s.burst_until = now + 1200
                s.events.append((now, f"POPS downshift+braking at {rpm}")); return
            elif now - s.down_at > DOWN_WAIT:
                s.down_at = 0; s.events.append((now, f"   downshift, no engine braking -> silent ({rpm})"))
            return
        if moving and s.armed and s.falling >= BRAKE_SAMPLES and s.fall_start >= THRESH and rpm >= BURST_MIN_RPM and now >= s.burst_until:
            s.armed = False; s.burst_until = now + 1200
            s.events.append((now, f"POPS lift-off from {s.fall_start}"))


def run(name, segments, expect):
    """segments: (duration_ms, rpm_from, rpm_to, speed[, reverse])"""
    e = Engine(); t = 0
    for seg in segments:
        dur, a, b, sp = seg[:4]; rev = seg[4] if len(seg) > 4 else False
        steps = max(1, dur // 100)
        for k in range(1, steps + 1):
            t += 100
            e.process(int(a + (b - a) * k / steps), sp, rev, t)
    real = [ev for _, ev in e.events if not ev.startswith("   ")]
    ok = expect(real)
    print(f"\n[{'OK ' if ok else 'BAD'}] {name}")
    for tt, ev in e.events: print(f"  {tt/1000:5.1f}s  {ev}")
    if not e.events: print("  (silent)")
    return ok


silent = lambda ev: not ev
count = lambda word, n: (lambda ev: sum(word in x for x in ev) == n)

results = [
    run("accelerate hard, upshifts at 4800-5000", [(1500, 1500, 4800, 40), (200, 4800, 3300, 60), (1500, 3300, 5000, 80), (200, 5000, 3700, 100), (1500, 3700, 5000, 120), (200, 5000, 3900, 130)], count("UPSHIFT", 3)),
    run("normal city driving, upshifts at 2000-2400 (must be silent)", [(2000, 900, 2200, 20), (200, 2200, 1500, 25), (2000, 1500, 2400, 40), (200, 2400, 1600, 45), (2000, 1600, 2300, 60), (200, 2300, 1500, 62)], silent),
    run("brisk, upshifts at 2900 (just under 3000: silent)", [(1500, 1500, 2900, 30), (200, 2900, 2000, 35), (1500, 2000, 2900, 60), (200, 2900, 2100, 65)], silent),
    run("lift-off from 4500 at speed", [(2000, 2000, 4500, 90), (3000, 4500, 2500, 80)], count("POPS", 1)),
    run("lift, re-accelerate, lift", [(2000, 2000, 4500, 90), (1500, 4500, 3500, 85), (1000, 3500, 4600, 95), (1500, 4600, 3500, 90)], count("POPS", 2)),
    run("lift-off from 3500 with threshold 4000 (silent)", [(2000, 2000, 3500, 80), (3000, 3500, 2200, 75)], silent),
    run("downshift to 3300 + engine braking", [(1000, 2200, 2200, 80), (300, 2200, 3300, 80), (2500, 3300, 2600, 70)], count("POPS", 1)),
    run("downshift to 2600 + braking (under 3000: silent)", [(1000, 1800, 1800, 60), (300, 1800, 2600, 60), (2500, 2600, 2000, 50)], silent),
    run("kickdown (downshift then accelerate)", [(1000, 2000, 2000, 90), (300, 2000, 3400, 90), (2000, 3400, 5200, 110)], silent),
    run("braking to a stop with auto downshifts at low rpm", [(2000, 2000, 1300, 60), (200, 1300, 1900, 50), (1500, 1900, 1200, 35), (200, 1200, 1700, 30), (1500, 1700, 900, 10), (1000, 900, 750, 0)], silent),
    run("highway cruise 2400 with small up/down", [(3000, 2400, 2500, 120), (2000, 2500, 2300, 118), (2000, 2300, 2450, 120)], silent),
    run("torque-converter lockup dips at 1800", [(1000, 1500, 1800, 40), (200, 1800, 1550, 40), (1000, 1550, 1700, 42)], silent),
    run("parked: two revs to 4500 / 5000 and let go -> pops twice", [(800, 800, 4500, 0), (800, 4500, 1200, 0), (800, 800, 5000, 0), (900, 5000, 800, 0)], count("POPS", 2)),
    run("parked: quick blip to 4000", [(1000, 800, 800, 0), (400, 800, 4000, 0), (700, 4000, 800, 0)], count("POPS", 1)),
    run("parked: small revs to 2500 (silent)", [(600, 800, 2500, 0), (600, 2500, 800, 0), (600, 800, 2800, 0), (600, 2800, 800, 0)], silent),
    run("parked: holding 3500 steady (silent until let go)", [(600, 800, 3500, 0), (3000, 3500, 3500, 0)], silent),
    run("arrive home: slow down, stop, idle (silent)", [(3000, 2200, 1200, 30), (2000, 1200, 800, 5), (3000, 800, 780, 0)], silent),
    run("reverse at high rpm (silent)", [(800, 800, 3500, 5, True), (800, 3500, 1000, 5, True), (500, 1000, 4500, 8, True), (600, 4500, 900, 3, True)], silent),
    run("burst running, rpm falls under 2200 -> stops", [(2000, 2000, 4200, 70), (2500, 4200, 1900, 50)], count("POPS", 1)),
    run("engine start + idle (silent)", [(300, 0, 0, 0), (500, 0, 1300, 0), (1500, 1300, 800, 0)], silent),
    run("rev limiter bounce at 5200 in 2nd (no pops while on throttle)", [(1200, 3000, 5200, 50), (100, 5200, 4900, 55), (100, 4900, 5200, 56), (100, 5200, 4900, 57), (100, 4900, 5200, 58)], lambda ev: not any("POPS" in x for x in ev)),
    run("uphill engine-braking downshift at 3200 then gentle throttle", [(1000, 2400, 2400, 70), (300, 2400, 3200, 70), (1500, 3200, 3300, 70)], silent),
]
print("\nALL OK" if all(results) else "\nSOME BAD")
