package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
)

type PrototypeScheduleEvent struct {
	Seq             int
	PlannedOffsetMs int64
	PayloadID       string
}

type PrototypeSchedule struct {
	ConditionID       string
	Version           string
	NominalIntervalMs int64
	TerminalOffsetMs  int64
	Events            []PrototypeScheduleEvent
	CanonicalBytes    []byte
	ScheduleHash      string
}

type prototypeScheduleConfig struct {
	initialOffsetMs  int64
	nominalMs        int64
	terminalDelayMs  int64
	firstPauseAfter  int
	firstPauseMs     int64
	secondPauseAfter int
	secondPauseMs    int64
}

func GeneratePrototypeSchedule(conditionID string) (PrototypeSchedule, error) {
	config, ok := prototypeScheduleConfigFor(conditionID)
	if !ok {
		return PrototypeSchedule{}, errors.New("unsupported prototype condition")
	}

	const (
		version    = "0.1"
		eventCount = 120
	)

	events := make([]PrototypeScheduleEvent, 0, eventCount)
	var canonical bytes.Buffer
	canonical.WriteString("seq,planned_offset_ms,payload_id\n")
	offset := config.initialOffsetMs
	for seq := 1; seq <= eventCount; seq++ {
		if seq > 1 {
			offset += config.nominalMs
			if seq == config.firstPauseAfter+1 {
				offset += config.firstPauseMs
			}
			if seq == config.secondPauseAfter+1 {
				offset += config.secondPauseMs
			}
		}
		payloadID := fmt.Sprintf("ref-%04d", seq)
		events = append(events, PrototypeScheduleEvent{
			Seq:             seq,
			PlannedOffsetMs: offset,
			PayloadID:       payloadID,
		})
		fmt.Fprintf(&canonical, "%d,%d,%s\n", seq, offset, payloadID)
	}

	canonicalBytes := append([]byte(nil), canonical.Bytes()...)
	digest := sha256.Sum256(canonicalBytes)
	return PrototypeSchedule{
		ConditionID:       conditionID,
		Version:           version,
		NominalIntervalMs: config.nominalMs,
		TerminalOffsetMs:  offset + config.terminalDelayMs,
		Events:            events,
		CanonicalBytes:    canonicalBytes,
		ScheduleHash:      hex.EncodeToString(digest[:]),
	}, nil
}

func prototypeScheduleConfigFor(conditionID string) (prototypeScheduleConfig, bool) {
	switch conditionID {
	case "baseline_v0.1":
		return prototypeScheduleConfig{
			initialOffsetMs: 200,
			nominalMs:       50,
			terminalDelayMs: 50,
		}, true
	case "slow_v0.1":
		return prototypeScheduleConfig{
			initialOffsetMs: 650,
			nominalMs:       125,
			terminalDelayMs: 125,
		}, true
	case "unstable_v0.1":
		return prototypeScheduleConfig{
			initialOffsetMs:  350,
			nominalMs:        65,
			terminalDelayMs:  65,
			firstPauseAfter:  40,
			firstPauseMs:     900,
			secondPauseAfter: 85,
			secondPauseMs:    1400,
		}, true
	default:
		return prototypeScheduleConfig{}, false
	}
}
