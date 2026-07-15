package main

import "testing"

func burstParams(seed int64, tokens int) StreamParams {
	return StreamParams{
		Seed:    seed,
		Tokens:  tokens,
		RateTps: 60,
		Median:  120,
		Sigma:   0.6,
		Burst: &Burst{
			ClusterTps:   100,
			PauseMs:      []int{300, 800},
			ClusterGeomP: 0.05,
		},
	}
}

// 同 seed 两次生成必须逐元素完全一致（确定性是 profile 冻结横比的前提）。
func TestGenerateTokensDeterministic(t *testing.T) {
	cases := []struct {
		name string
		p    StreamParams
	}{
		{"uniform", StreamParams{Seed: 1001, Tokens: 600, RateTps: 40, Median: 120, Sigma: 0.6}},
		{"burst", burstParams(2001, 300)},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			a := GenerateTokens(tc.p)
			b := GenerateTokens(tc.p)
			if len(a) != tc.p.Tokens || len(b) != tc.p.Tokens {
				t.Fatalf("length mismatch: %d %d want %d", len(a), len(b), tc.p.Tokens)
			}
			for i := range a {
				if a[i] != b[i] {
					t.Fatalf("seq %d differs: %+v vs %+v", i, a[i], b[i])
				}
			}
		})
	}
}

func TestGenerateTokensSeedSensitivity(t *testing.T) {
	a := GenerateTokens(StreamParams{Seed: 1, Tokens: 100, RateTps: 40, Median: 120, Sigma: 0.6})
	b := GenerateTokens(StreamParams{Seed: 2, Tokens: 100, RateTps: 40, Median: 120, Sigma: 0.6})
	same := true
	for i := range a {
		if a[i].Size != b[i].Size {
			same = false
			break
		}
	}
	if same {
		t.Fatal("different seeds produced identical size sequences")
	}
}

func TestGenerateTokensClampAndSchedule(t *testing.T) {
	p := StreamParams{Seed: 42, Tokens: 2000, RateTps: 40, Median: 120, Sigma: 0.6}
	specs := GenerateTokens(p)
	for i, s := range specs {
		if s.Size < tokenBytesMin || s.Size > tokenBytesMax {
			t.Fatalf("seq %d size %d out of clamp [%d,%d]", i, s.Size, tokenBytesMin, tokenBytesMax)
		}
		// 均匀模式：间隔 = 1e6/40 = 25000us。
		want := int64(i) * 25000
		if s.SchedUs != want {
			t.Fatalf("seq %d sched %d want %d", i, s.SchedUs, want)
		}
	}
}

func TestGenerateTokensBurstMonotonic(t *testing.T) {
	specs := GenerateTokens(burstParams(2002, 800))
	if len(specs) != 800 {
		t.Fatalf("got %d specs", len(specs))
	}
	var prev int64 = -1
	pauses := 0
	for i, s := range specs {
		if s.SchedUs <= prev && i > 0 {
			t.Fatalf("schedule not strictly increasing at seq %d: %d after %d", i, s.SchedUs, prev)
		}
		if i > 0 && s.SchedUs-prev >= 300_000 {
			pauses++
		}
		prev = s.SchedUs
	}
	// geom_p=0.05 → 期望每 ~20 token 一次簇间停顿，800 token 应出现多次。
	if pauses == 0 {
		t.Fatal("burst mode produced no inter-cluster pauses >= 300ms")
	}
}
