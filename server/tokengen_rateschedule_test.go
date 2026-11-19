package main

import (
	"math"
	"testing"
)

// 非平稳解码 TPS（§3.2）：分段线性 tpsAtSchedule + GenerateTokens 变速累积。

func TestTpsAtSchedule(t *testing.T) {
	sched := []RatePoint{{AtFrac: 0, Tps: 100}, {AtFrac: 1, Tps: 20}}
	cases := []struct {
		frac, want float64
	}{
		{-0.5, 100}, // 端点外 clamp
		{0.0, 100},
		{0.5, 60}, // 线性中点
		{1.0, 20},
		{2.0, 20}, // 端点外 clamp
	}
	for _, c := range cases {
		if got := tpsAtSchedule(sched, c.frac, 40); math.Abs(got-c.want) > 1e-9 {
			t.Fatalf("tpsAtSchedule(%.2f) = %.4f, want %.4f", c.frac, got, c.want)
		}
	}
	// 空曲线 → fallback
	if got := tpsAtSchedule(nil, 0.5, 40); got != 40 {
		t.Fatalf("empty schedule = %.2f, want fallback 40", got)
	}
}

func TestGenerateTokensRateScheduleGapsGrow(t *testing.T) {
	// TPS 100→25：间隔应从 ~10ms 增长到 ~40ms（前快后慢，非平稳解码）。
	p := StreamParams{
		Seed: 5, Tokens: 20, RateTps: 40, Median: 120, Sigma: 0.6,
		RateSchedule: []RatePoint{{AtFrac: 0, Tps: 100}, {AtFrac: 1, Tps: 25}},
	}
	specs := GenerateTokens(p)
	if len(specs) != 20 {
		t.Fatalf("len = %d", len(specs))
	}
	// 首 token 在 0（无 TTFT 注入）。
	if specs[0].SchedUs != 0 {
		t.Fatalf("first sched = %d, want 0", specs[0].SchedUs)
	}
	// 间隔非递减（容 ±1us 舍入），且末段明显大于首段。
	firstGap := specs[1].SchedUs - specs[0].SchedUs
	lastGap := specs[19].SchedUs - specs[18].SchedUs
	for i := 2; i < len(specs); i++ {
		g0 := specs[i-1].SchedUs - specs[i-2].SchedUs
		g1 := specs[i].SchedUs - specs[i-1].SchedUs
		if g1 < g0-1 {
			t.Fatalf("gap decreased at seq %d: %d -> %d", i, g0, g1)
		}
	}
	if lastGap < 3*firstGap {
		t.Fatalf("non-stationary not evident: firstGap=%d lastGap=%d", firstGap, lastGap)
	}
	// 间隔由本 token 处（frac=i/(N-1)）的瞬时 TPS 决定：首间隔用 frac=0（100tps→10000us），
	// 末间隔用 frac=18/19（≈28.95tps→~34546us）。期望绑定模型函数本身，避免手算漂移；容 ±2us 舍入。
	wantFirst := 1e6 / tpsAtSchedule(p.RateSchedule, 0.0, p.RateTps)
	wantLast := 1e6 / tpsAtSchedule(p.RateSchedule, 18.0/19.0, p.RateTps)
	if math.Abs(float64(firstGap)-wantFirst) > 2 {
		t.Fatalf("firstGap = %d, want ~%.0f", firstGap, wantFirst)
	}
	if math.Abs(float64(lastGap)-wantLast) > 2 {
		t.Fatalf("lastGap = %d, want ~%.0f", lastGap, wantLast)
	}
}

func TestGenerateTokensRateScheduleSizesMatchConstantPath(t *testing.T) {
	// 变速只改 sched，不改 rng 消耗顺序 → 大小序列与常速路径逐 token 相同。
	base := StreamParams{Seed: 42, Tokens: 30, RateTps: 40, Median: 120, Sigma: 0.6}
	sched := base
	sched.RateSchedule = []RatePoint{{AtFrac: 0, Tps: 80}, {AtFrac: 1, Tps: 30}}
	b := GenerateTokens(base)
	s := GenerateTokens(sched)
	for i := range b {
		if b[i].Size != s[i].Size {
			t.Fatalf("size diverged at seq %d: constant=%d scheduled=%d", i, b[i].Size, s[i].Size)
		}
	}
}

func TestGenerateTokensRateScheduleWithTtftInject(t *testing.T) {
	p := StreamParams{
		Seed: 9, Tokens: 10, RateTps: 40, Median: 120, Sigma: 0.6,
		TtftInjectUs: 50_000,
		RateSchedule: []RatePoint{{AtFrac: 0, Tps: 100}, {AtFrac: 1, Tps: 50}},
	}
	specs := GenerateTokens(p)
	if specs[0].SchedUs != 50_000 {
		t.Fatalf("first sched with inject = %d, want 50000", specs[0].SchedUs)
	}
}

func TestGenerateTokensRateScheduleDeterministic(t *testing.T) {
	p := StreamParams{
		Seed: 123, Tokens: 25, RateTps: 40, Median: 120, Sigma: 0.6,
		RateSchedule: []RatePoint{{AtFrac: 0, Tps: 90}, {AtFrac: 0.5, Tps: 60}, {AtFrac: 1, Tps: 30}},
	}
	a := GenerateTokens(p)
	b := GenerateTokens(p)
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("non-deterministic at seq %d", i)
		}
	}
}
