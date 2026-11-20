package main

import "testing"

// 流内 think 驻留（§3.2）：seq≥AtSeq 右移 DwellUs、可叠加、只改时刻表不改大小/确定性。

func TestGenerateTokensThinkInjectionShifts(t *testing.T) {
	base := StreamParams{Seed: 7, Tokens: 10, RateTps: 40, Median: 120, Sigma: 0.6}
	inj := base
	inj.ThinkInjections = []ThinkInjection{{AtSeq: 5, DwellUs: 300_000}}
	b := GenerateTokens(base)
	o := GenerateTokens(inj)
	for i := range b {
		want := b[i].SchedUs
		if i >= 5 {
			want += 300_000
		}
		if o[i].SchedUs != want {
			t.Fatalf("seq %d sched = %d, want %d", i, o[i].SchedUs, want)
		}
		if o[i].Size != b[i].Size {
			t.Fatalf("seq %d size changed by think injection", i)
		}
	}
	// think 前后间隔：seq4→5 应比原多 300ms，其余不变。
	if (o[5].SchedUs - o[4].SchedUs) != (b[5].SchedUs-b[4].SchedUs)+300_000 {
		t.Fatalf("think gap at seq5 not +300ms")
	}
	if (o[6].SchedUs - o[5].SchedUs) != (b[6].SchedUs - b[5].SchedUs) {
		t.Fatalf("post-think interval changed")
	}
}

func TestGenerateTokensThinkInjectionCumulative(t *testing.T) {
	base := StreamParams{Seed: 3, Tokens: 12, RateTps: 40, Median: 120, Sigma: 0.6}
	inj := base
	inj.ThinkInjections = []ThinkInjection{{AtSeq: 3, DwellUs: 100_000}, {AtSeq: 8, DwellUs: 200_000}}
	b := GenerateTokens(base)
	o := GenerateTokens(inj)
	for i := range b {
		var shift int64
		if i >= 3 {
			shift += 100_000
		}
		if i >= 8 {
			shift += 200_000
		}
		if o[i].SchedUs != b[i].SchedUs+shift {
			t.Fatalf("seq %d sched = %d, want %d (cumulative)", i, o[i].SchedUs, b[i].SchedUs+shift)
		}
	}
}

func TestGenerateTokensThinkInjectionEmptyUnchanged(t *testing.T) {
	p := StreamParams{Seed: 5, Tokens: 20, RateTps: 40, Median: 120, Sigma: 0.6}
	a := GenerateTokens(p)
	p2 := p
	p2.ThinkInjections = nil
	b := GenerateTokens(p2)
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("empty think must be unchanged; diff at %d", i)
		}
	}
}

func TestGenerateTokensThinkInjectionComposesWithTtftAndSchedule(t *testing.T) {
	// think 与 TTFT 注入 + 非平稳 TPS 叠加：think 是最后一层右移。
	p := StreamParams{
		Seed: 9, Tokens: 10, RateTps: 40, Median: 120, Sigma: 0.6,
		TtftInjectUs:    50_000,
		RateSchedule:    []RatePoint{{AtFrac: 0, Tps: 100}, {AtFrac: 1, Tps: 50}},
		ThinkInjections: []ThinkInjection{{AtSeq: 4, DwellUs: 400_000}},
	}
	pNoThink := p
	pNoThink.ThinkInjections = nil
	withThink := GenerateTokens(p)
	base := GenerateTokens(pNoThink)
	for i := range base {
		want := base[i].SchedUs
		if i >= 4 {
			want += 400_000
		}
		if withThink[i].SchedUs != want {
			t.Fatalf("seq %d composed sched = %d, want %d", i, withThink[i].SchedUs, want)
		}
	}
}
