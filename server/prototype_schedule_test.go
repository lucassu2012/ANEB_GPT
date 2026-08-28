package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"testing"
)

func TestGeneratePrototypeScheduleBaselineCanonicalAndDeterministic(t *testing.T) {
	first, err := GeneratePrototypeSchedule("baseline_v0.1")
	if err != nil {
		t.Fatalf("baseline schedule: %v", err)
	}
	second, err := GeneratePrototypeSchedule("baseline_v0.1")
	if err != nil {
		t.Fatalf("second baseline schedule: %v", err)
	}

	if first.ConditionID != "baseline_v0.1" {
		t.Fatalf("condition id = %q", first.ConditionID)
	}
	if first.Version != "0.1" {
		t.Fatalf("version = %q", first.Version)
	}
	if first.NominalIntervalMs != 50 {
		t.Fatalf("nominal interval = %d", first.NominalIntervalMs)
	}
	if first.TerminalOffsetMs != 6200 {
		t.Fatalf("terminal offset = %d", first.TerminalOffsetMs)
	}
	if len(first.Events) != 120 {
		t.Fatalf("event count = %d", len(first.Events))
	}
	if first.Events[0].Seq != 1 || first.Events[0].PlannedOffsetMs != 200 || first.Events[0].PayloadID != "ref-0001" {
		t.Fatalf("first event = %+v", first.Events[0])
	}
	last := first.Events[len(first.Events)-1]
	if last.Seq != 120 || last.PlannedOffsetMs != 6150 || last.PayloadID != "ref-0120" {
		t.Fatalf("last event = %+v", last)
	}

	var expected bytes.Buffer
	expected.WriteString("seq,planned_offset_ms,payload_id\n")
	for seq := 1; seq <= 120; seq++ {
		fmt.Fprintf(&expected, "%d,%d,ref-%04d\n", seq, 200+(seq-1)*50, seq)
	}
	if !bytes.Equal(first.CanonicalBytes, expected.Bytes()) {
		t.Fatalf("canonical schedule bytes differ")
	}
	digest := sha256.Sum256(first.CanonicalBytes)
	const expectedHash = "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e"
	if got := hex.EncodeToString(digest[:]); got != expectedHash {
		t.Fatalf("computed schedule hash = %s", got)
	}
	if first.ScheduleHash != expectedHash {
		t.Fatalf("reported schedule hash = %s", first.ScheduleHash)
	}
	if bytes.Contains(first.CanonicalBytes, []byte("done")) {
		t.Fatalf("terminal event leaked into schedule bytes")
	}
	if !bytes.Equal(first.CanonicalBytes, second.CanonicalBytes) || first.ScheduleHash != second.ScheduleHash {
		t.Fatalf("schedule is not deterministic")
	}
}

func TestGeneratePrototypeScheduleSlowAndUnstableCanonical(t *testing.T) {
	tests := []struct {
		condition        string
		nominal          int64
		initial          int64
		terminalDelay    int64
		firstPauseAfter  int
		firstPauseMs     int64
		secondPauseAfter int
		secondPauseMs    int64
		expectedHash     string
		expectedEnd      int64
	}{
		{
			condition:     "slow_v0.1",
			nominal:       125,
			initial:       650,
			terminalDelay: 125,
			expectedHash:  "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
			expectedEnd:   15650,
		},
		{
			condition:        "unstable_v0.1",
			nominal:          65,
			initial:          350,
			firstPauseAfter:  40,
			firstPauseMs:     900,
			secondPauseAfter: 85,
			secondPauseMs:    1400,
			expectedHash:     "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
			expectedEnd:      10450,
		},
	}

	for _, tc := range tests {
		t.Run(tc.condition, func(t *testing.T) {
			schedule, err := GeneratePrototypeSchedule(tc.condition)
			if err != nil {
				t.Fatalf("schedule: %v", err)
			}
			if schedule.NominalIntervalMs != tc.nominal {
				t.Fatalf("nominal interval = %d", schedule.NominalIntervalMs)
			}
			if schedule.TerminalOffsetMs != tc.expectedEnd {
				t.Fatalf("terminal offset = %d", schedule.TerminalOffsetMs)
			}
			if len(schedule.Events) != 120 {
				t.Fatalf("event count = %d", len(schedule.Events))
			}

			var expected bytes.Buffer
			expected.WriteString("seq,planned_offset_ms,payload_id\n")
			offset := tc.initial
			for seq := 1; seq <= 120; seq++ {
				if seq > 1 {
					offset += tc.nominal
					if seq == tc.firstPauseAfter+1 {
						offset += tc.firstPauseMs
					}
					if seq == tc.secondPauseAfter+1 {
						offset += tc.secondPauseMs
					}
				}
				fmt.Fprintf(&expected, "%d,%d,ref-%04d\n", seq, offset, seq)
			}
			if !bytes.Equal(schedule.CanonicalBytes, expected.Bytes()) {
				t.Fatalf("canonical schedule bytes differ")
			}
			digest := sha256.Sum256(schedule.CanonicalBytes)
			if got := hex.EncodeToString(digest[:]); got != tc.expectedHash {
				t.Fatalf("computed schedule hash = %s", got)
			}
			if schedule.ScheduleHash != tc.expectedHash {
				t.Fatalf("reported schedule hash = %s", schedule.ScheduleHash)
			}
		})
	}
}

func TestGeneratePrototypeScheduleRejectsUnknownCondition(t *testing.T) {
	if _, err := GeneratePrototypeSchedule("client_override_v9"); err == nil {
		t.Fatalf("unknown condition was accepted")
	}
}
