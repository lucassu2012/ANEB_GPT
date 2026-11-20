package main

import "testing"

// 每模型字节直方图（§3.2）：确定性抽样、分布近似权重、opt-in 不改 lognormal。

func TestGenerateTokensHistogramDeterministic(t *testing.T) {
	p := StreamParams{
		Seed: 7, Tokens: 50, RateTps: 40,
		SizeHistogram: []SizeBin{{Size: 100, Weight: 1}, {Size: 500, Weight: 3}},
	}
	a := GenerateTokens(p)
	b := GenerateTokens(p)
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("non-deterministic at %d", i)
		}
	}
}

func TestGenerateTokensHistogramOnlyDeclaredSizes(t *testing.T) {
	p := StreamParams{
		Seed: 11, Tokens: 200, RateTps: 40,
		SizeHistogram: []SizeBin{{Size: 100, Weight: 1}, {Size: 500, Weight: 3}},
	}
	specs := GenerateTokens(p)
	for i, s := range specs {
		if s.Size != 100 && s.Size != 500 {
			t.Fatalf("seq %d size = %d, want 100 or 500 (histogram declares only these)", i, s.Size)
		}
	}
}

func TestGenerateTokensHistogramApproximatesWeights(t *testing.T) {
	// 权重 1:3 → 约 25% / 75%，大样本下经验频率应接近（确定性 seed，断言稳定）。
	const n = 2000
	p := StreamParams{
		Seed: 20260716, Tokens: n, RateTps: 40,
		SizeHistogram: []SizeBin{{Size: 100, Weight: 1}, {Size: 500, Weight: 3}},
	}
	specs := GenerateTokens(p)
	c100 := 0
	for _, s := range specs {
		if s.Size == 100 {
			c100++
		}
	}
	frac := float64(c100) / n
	if frac < 0.22 || frac > 0.28 {
		t.Fatalf("size=100 fraction = %.3f, want ~0.25 (weight 1 of 4)", frac)
	}
}

func TestGenerateTokensHistogramEmptyFallsBackToLognormal(t *testing.T) {
	// 无直方图 → 与显式 nil 一致，且落在 lognormal clamp 区间。
	p := StreamParams{Seed: 3, Tokens: 30, RateTps: 40, Median: 120, Sigma: 0.6}
	a := GenerateTokens(p)
	p2 := p
	p2.SizeHistogram = nil
	b := GenerateTokens(p2)
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("empty histogram must equal lognormal; diff at %d", i)
		}
		if a[i].Size < tokenBytesMin || a[i].Size > tokenBytesMax {
			t.Fatalf("lognormal size %d out of clamp at %d", a[i].Size, i)
		}
	}
}

func TestGenerateTokensHistogramSingleBin(t *testing.T) {
	p := StreamParams{
		Seed: 5, Tokens: 20, RateTps: 40,
		SizeHistogram: []SizeBin{{Size: 256, Weight: 1}},
	}
	for _, s := range GenerateTokens(p) {
		if s.Size != 256 {
			t.Fatalf("single-bin size = %d, want 256", s.Size)
		}
	}
}
