package main

import "testing"

// TTFT 注入（§3.4）：整表右移 TtftInjectUs，间隔不变、大小序列不变、确定性不变。

func TestGenerateTokensTtftInjectOffset(t *testing.T) {
	base := StreamParams{Seed: 7, Tokens: 12, RateTps: 40, Median: 120, Sigma: 0.6}
	inj := base
	inj.TtftInjectUs = 250_000 // 250ms
	b := GenerateTokens(base)
	o := GenerateTokens(inj)
	if len(b) != len(o) || len(b) != 12 {
		t.Fatalf("len mismatch: base=%d inj=%d", len(b), len(o))
	}
	// 首 token 计划于注入偏移（base 首 token 在 0）。
	if b[0].SchedUs != 0 {
		t.Fatalf("base first sched = %d, want 0", b[0].SchedUs)
	}
	if o[0].SchedUs != 250_000 {
		t.Fatalf("injected first sched = %d, want 250000", o[0].SchedUs)
	}
	for i := range b {
		if o[i].SchedUs != b[i].SchedUs+250_000 {
			t.Fatalf("seq %d sched = %d, want %d (uniform +250000)", i, o[i].SchedUs, b[i].SchedUs+250_000)
		}
		if o[i].Size != b[i].Size {
			t.Fatalf("seq %d size changed by injection: %d vs %d (rng must be unaffected)", i, o[i].Size, b[i].Size)
		}
	}
	// token 间间隔保持不变（dwell 只进"起点→首 token"，不污染 ITL）。
	for i := 1; i < len(o); i++ {
		if (o[i].SchedUs - o[i-1].SchedUs) != (b[i].SchedUs - b[i-1].SchedUs) {
			t.Fatalf("inter-token interval changed at seq %d", i)
		}
	}
}

func TestGenerateTokensTtftInjectZeroUnchanged(t *testing.T) {
	p := StreamParams{Seed: 3, Tokens: 40, RateTps: 40, Median: 120, Sigma: 0.6}
	a := GenerateTokens(p)
	p2 := p
	p2.TtftInjectUs = 0
	b := GenerateTokens(p2)
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("TtftInjectUs=0 must be byte-identical to no-inject; diff at %d", i)
		}
	}
}

func TestGenerateTokensTtftInjectBurst(t *testing.T) {
	burst := &Burst{ClusterTps: 100, PauseMs: []int{300, 800}, ClusterGeomP: 0.2}
	base := StreamParams{Seed: 11, Tokens: 30, Median: 120, Sigma: 0.6, Burst: burst}
	inj := base
	inj.TtftInjectUs = 100_000
	b := GenerateTokens(base)
	o := GenerateTokens(inj)
	for i := range b {
		if o[i].SchedUs != b[i].SchedUs+100_000 {
			t.Fatalf("burst seq %d sched = %d, want %d", i, o[i].SchedUs, b[i].SchedUs+100_000)
		}
		if o[i].Size != b[i].Size {
			t.Fatalf("burst seq %d size changed by injection", i)
		}
	}
}

func TestGenerateTokensTtftInjectDeterministic(t *testing.T) {
	p := StreamParams{Seed: 99, Tokens: 25, RateTps: 40, Median: 120, Sigma: 0.6, TtftInjectUs: 180_000}
	a := GenerateTokens(p)
	b := GenerateTokens(p)
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("same params (incl inject) must reproduce identical specs; diff at %d", i)
		}
	}
}
