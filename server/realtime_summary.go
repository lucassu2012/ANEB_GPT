package main

import (
	"io"
	"log"
	"os"
	"sync"
)

// realtimeProtocolSummary is deliberately bounded to protocol counters and
// process/run identities. Client payloads, addresses, headers, session IDs,
// turn IDs, and timing samples are not representable in this contract.
type realtimeProtocolSummary struct {
	InstanceID       string
	RunID            string
	Sessions         int
	Turns            int
	UplinkFrames     int
	DownlinkFrames   int
	InterruptedTurns int
	ProtocolOK       bool
}

type realtimeProtocolSummaryEmitter interface {
	TryEmit(realtimeProtocolSummary) bool
}

type realtimeProtocolSummaryEmitterFunc func(realtimeProtocolSummary) bool

func (emit realtimeProtocolSummaryEmitterFunc) TryEmit(summary realtimeProtocolSummary) bool {
	return emit(summary)
}

type realtimeProtocolSummaryLogger struct {
	logger *log.Logger
}

func (sink *realtimeProtocolSummaryLogger) TryEmit(summary realtimeProtocolSummary) bool {
	sink.logger.Printf(
		"ANEB_REALTIME_SUMMARY instance_id=%s run_id=%s sessions=%d turns=%d uplink_frames=%d downlink_frames=%d interrupted_turns=%d protocol_ok=%t",
		summary.InstanceID,
		summary.RunID,
		summary.Sessions,
		summary.Turns,
		summary.UplinkFrames,
		summary.DownlinkFrames,
		summary.InterruptedTurns,
		summary.ProtocolOK,
	)
	return true
}

func newRealtimeProtocolSummaryLogger(output io.Writer) realtimeProtocolSummaryEmitter {
	return &realtimeProtocolSummaryLogger{logger: log.New(output, "", 0)}
}

var processRealtimeProtocolSummarySink struct {
	sync.Once
	sink realtimeProtocolSummaryEmitter
}

func defaultRealtimeProtocolSummarySink() realtimeProtocolSummaryEmitter {
	processRealtimeProtocolSummarySink.Do(func() {
		processRealtimeProtocolSummarySink.sink = newRealtimeProtocolSummaryLogger(os.Stderr)
	})
	return processRealtimeProtocolSummarySink.sink
}
