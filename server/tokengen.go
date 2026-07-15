package main

import (
	"math"
	"math/rand"
)

// token 大小 clamp 区间（字节）。
const (
	tokenBytesMin = 30
	tokenBytesMax = 2000
)

// TokenSpec 是时刻表中的一个 token：计划发出时刻（相对流起点的微秒偏移）与 payload 字节数。
type TokenSpec struct {
	SchedUs int64 // 相对流起点的计划发出偏移
	Size    int   // base64 编码前的原始 payload 字节数
}

// StreamParams 是 token 发生器的全部输入。同一组参数（尤其同 Seed）
// 必须产生完全相同的 TokenSpec 序列——profile 冻结 + seed 固定是横比前提。
type StreamParams struct {
	Seed    int64
	Tokens  int
	RateTps float64
	Median  float64 // token_bytes.median
	Sigma   float64 // token_bytes.sigma
	Burst   *Burst  // nil = 均匀 1/rate_tps
}

// GenerateTokens 生成确定性的 token 时刻表与大小序列。
//
// 随机数消耗顺序（固定，保证确定性）：
//   - 均匀模式：每 token 依次 1 次 NormFloat64（大小）。
//   - burst 模式：每簇开始 1 次 Float64（簇长，几何分布），
//     簇内每 token 1 次 NormFloat64（大小），
//     簇结束且还有后续 token 时 1 次 Float64（簇间停顿）。
//
// 大小分布：lognormal，size = median * exp(sigma*N(0,1))，clamp [30, 2000]。
func GenerateTokens(p StreamParams) []TokenSpec {
	if p.Tokens <= 0 {
		return nil
	}
	rng := rand.New(rand.NewSource(p.Seed))
	specs := make([]TokenSpec, p.Tokens)

	drawSize := func() int {
		v := p.Median * math.Exp(p.Sigma*rng.NormFloat64())
		n := int(math.Round(v))
		if n < tokenBytesMin {
			n = tokenBytesMin
		}
		if n > tokenBytesMax {
			n = tokenBytesMax
		}
		return n
	}

	if p.Burst == nil {
		intervalUs := 1e6 / p.RateTps
		for i := 0; i < p.Tokens; i++ {
			specs[i] = TokenSpec{
				SchedUs: int64(math.Round(float64(i) * intervalUs)),
				Size:    drawSize(),
			}
		}
		return specs
	}

	// burst 模式：簇内 cluster_tps，簇长几何分布（均值 1/p），簇间停顿均匀 [min,max] ms。
	intraUs := 1e6 / p.Burst.ClusterTps
	pauseMinMs, pauseMaxMs := 0.0, 0.0
	if len(p.Burst.PauseMs) >= 2 {
		pauseMinMs = float64(p.Burst.PauseMs[0])
		pauseMaxMs = float64(p.Burst.PauseMs[1])
	} else if len(p.Burst.PauseMs) == 1 {
		pauseMinMs = float64(p.Burst.PauseMs[0])
		pauseMaxMs = pauseMinMs
	}

	t := 0.0 // 当前 token 的计划偏移（us，浮点累计自簇锚点，见下）
	i := 0
	for i < p.Tokens {
		clusterLen := geometric(rng, p.Burst.ClusterGeomP)
		last := 0.0
		for j := 0; j < clusterLen && i < p.Tokens; j++ {
			sched := t + float64(j)*intraUs
			specs[i] = TokenSpec{SchedUs: int64(math.Round(sched)), Size: drawSize()}
			last = sched
			i++
		}
		if i < p.Tokens {
			pauseMs := pauseMinMs + rng.Float64()*(pauseMaxMs-pauseMinMs)
			t = last + pauseMs*1000.0
		}
	}
	return specs
}

// geometric 返回 >=1 的几何分布样本（成功概率 p，均值 1/p）。
// 用逆变换法：一次 Float64 消耗，保证随机数消耗量与样本值无关之外的确定性。
func geometric(rng *rand.Rand, p float64) int {
	if p <= 0 || p >= 1 {
		return 1
	}
	u := rng.Float64()
	// P(X >= k) = (1-p)^(k-1)；X = floor(ln(1-u)/ln(1-p)) + 1
	k := int(math.Floor(math.Log(1-u)/math.Log(1-p))) + 1
	if k < 1 {
		k = 1
	}
	return k
}
