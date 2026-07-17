package gateway

import "testing"

func TestRestorableBaselineCanonicalizesSupportedFQCodel(t *testing.T) {
	first, err := parseRestorableBaseline("qdisc fq_codel 0: root refcnt 2 limit 10240p flows 1024 quantum 1514 target 5ms interval 100ms memory_limit 32Mb ecn drop_batch 64")
	if err != nil {
		t.Fatal(err)
	}
	second, err := parseRestorableBaseline("qdisc fq_codel 8001: root target 5ms limit 10240p flows 1024 quantum 1514 interval 100ms memory_limit 32Mb drop_batch 64 ecn")
	if err != nil {
		t.Fatal(err)
	}
	if !first.equal(second) {
		t.Fatalf("semantic fq_codel baselines differ: %+v %+v", first, second)
	}
	if len(first.Args) < 2 || first.Args[0] != "limit" || first.Args[1] != "10240" {
		t.Fatalf("fq_codel restore args are not executable: %v", first.Args)
	}
}

func TestRestorableBaselineDistinguishesFQFromFQCodel(t *testing.T) {
	fq, err := parseRestorableBaseline("qdisc fq 8001: root limit 10000p flow_limit 100p")
	if err != nil {
		t.Fatal(err)
	}
	fqCodel, err := parseRestorableBaseline("qdisc fq_codel 0: root limit 10240p flows 1024")
	if err != nil {
		t.Fatal(err)
	}
	if fq.equal(fqCodel) {
		t.Fatal("fq and fq_codel were treated as interchangeable baselines")
	}
	quantumBaseline, err := parseRestorableBaseline("qdisc fq 8001: root quantum 3028b initial_quantum 15140b")
	if err != nil {
		t.Fatal(err)
	}
	if got := quantumBaseline.Args; len(got) != 4 || got[1] != "3028" || got[3] != "15140" {
		t.Fatalf("fq byte values were not converted to executable integers: %v", got)
	}
}

func TestRestorableBaselineRejectsUnexecutableOrUnknownConfiguration(t *testing.T) {
	for _, value := range []string{
		"qdisc tbf 8001: root rate 1Mbit burst 32Kb latency 400ms",
		"qdisc fq_codel 0: root malicious /tmp/payload",
		"qdisc fq_codel 0: root limit 100p limit 200p",
		"qdisc fq_codel 0: root ecn noecn",
	} {
		if _, err := parseRestorableBaseline(value); err == nil {
			t.Fatalf("unsafe baseline accepted: %s", value)
		}
	}
}
