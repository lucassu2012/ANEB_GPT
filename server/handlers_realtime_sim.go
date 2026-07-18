package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/gorilla/websocket"
)

const (
	realtimeSessionContract = "aneb-realtime-session-v1"
	realtimeMaxPlanBytes    = 1 << 20
	realtimeMaxTurns        = 32
	realtimeMaxFrames       = 10_000
	realtimeMaxFrameBytes   = 4_096
	realtimeReadTimeout     = 45 * time.Second
	realtimeWriteTimeout    = 10 * time.Second
)

var realtimeUpgrader = websocket.Upgrader{
	ReadBufferSize:  64 * 1024,
	WriteBufferSize: 64 * 1024,
	CheckOrigin: func(r *http.Request) bool {
		// Native ANEB clients do not send Origin. Browser cross-origin use is not part of this API.
		return r.Header.Get("Origin") == ""
	},
}

type realtimeSessionPlan struct {
	ContractVersion string             `json:"contract_version"`
	SessionID       string             `json:"session_id"`
	Seed            int64              `json:"seed"`
	SetupMs         float64            `json:"setup_ms"`
	FrameMs         int                `json:"frame_ms"`
	Turns           []realtimeTurnPlan `json:"turns"`
}

type realtimeTurnPlan struct {
	TurnID                string  `json:"turn_id"`
	TurnIndex             int     `json:"turn_index"`
	StartAfterPreviousMs  float64 `json:"start_after_previous_ms"`
	UplinkFrames          int     `json:"uplink_frames"`
	UplinkFrameBytes      int     `json:"uplink_frame_bytes"`
	ResponseWaitMs        float64 `json:"response_wait_ms"`
	PlannedDownlinkFrames int     `json:"planned_downlink_frames"`
	DownlinkFrameBytes    int     `json:"downlink_frame_bytes"`
	Interrupted           bool    `json:"interrupted"`
	BargeInAfterFrames    *int    `json:"barge_in_after_frames"`
	ExpectedStopWithinMs  *int    `json:"expected_stop_within_ms"`
}

type realtimeControl struct {
	Type         string `json:"type"`
	SessionID    string `json:"session_id,omitempty"`
	TurnID       string `json:"turn_id,omitempty"`
	TurnIndex    int    `json:"turn_index,omitempty"`
	PingID       int64  `json:"ping_id,omitempty"`
	ClientMonoUs int64  `json:"client_mono_us,omitempty"`
}

type realtimeInbound struct {
	messageType int
	data        []byte
}

type realtimeTurnSummary struct {
	Type                    string `json:"type"`
	TurnID                  string `json:"turn_id"`
	TurnIndex               int    `json:"turn_index"`
	UplinkFramesExpected    int    `json:"uplink_frames_expected"`
	UplinkFramesReceived    int    `json:"uplink_frames_received"`
	DownlinkFramesPlanned   int    `json:"downlink_frames_planned"`
	DownlinkFramesEmitted   int    `json:"downlink_frames_emitted"`
	CommitRecvUs            int64  `json:"commit_recv_us"`
	FirstDownlinkSchedUs    int64  `json:"first_downlink_sched_us"`
	FirstDownlinkPreWriteUs int64  `json:"first_downlink_pre_write_us"`
	BargeInReceived         bool   `json:"barge_in_received"`
	BargeInRecvUs           int64  `json:"barge_in_recv_us,omitempty"`
	StopAckUs               int64  `json:"stop_ack_us,omitempty"`
	ProtocolOK              bool   `json:"protocol_ok"`
}

func (a *app) handleRealtimeSim(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	controlledDisconnectAfterTurn, err := realtimeControlledDisconnectAfterTurn(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	conn, err := realtimeUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()
	conn.SetReadLimit(realtimeMaxPlanBytes)
	_ = conn.SetReadDeadline(time.Now().Add(realtimeReadTimeout))

	messageType, encodedPlan, err := conn.ReadMessage()
	if err != nil {
		return
	}
	if messageType != websocket.TextMessage {
		_ = realtimeWriteError(conn, "session plan must be a text message")
		return
	}
	plan, err := decodeRealtimePlan(encodedPlan)
	if err != nil {
		_ = realtimeWriteError(conn, err.Error())
		return
	}

	incoming := make(chan realtimeInbound, 256)
	readErrors := make(chan error, 1)
	go func() {
		for {
			_ = conn.SetReadDeadline(time.Now().Add(realtimeReadTimeout))
			kind, data, readErr := conn.ReadMessage()
			if readErr != nil {
				select {
				case readErrors <- readErr:
				default:
				}
				return
			}
			select {
			case incoming <- realtimeInbound{messageType: kind, data: data}:
			default:
				select {
				case readErrors <- fmt.Errorf("realtime inbound queue overflow"):
				default:
				}
				return
			}
		}
	}()

	readyDeadline := time.Now().Add(durationMillis(plan.SetupMs))
	if err := realtimeWaitQuiet(r, conn, incoming, readErrors, readyDeadline); err != nil {
		return
	}
	readyUs := nowMicros()
	if err := realtimeWriteJSON(conn, map[string]any{
		"type":             "session_ready",
		"contract_version": realtimeSessionContract,
		"session_id":       plan.SessionID,
		"ready_us":         readyUs,
		"observed":         r.RemoteAddr,
	}); err != nil {
		return
	}

	summaries := make([]realtimeTurnSummary, 0, len(plan.Turns))
	for _, turn := range plan.Turns {
		summary, runErr := realtimeRunTurn(r, conn, incoming, readErrors, plan, turn)
		if runErr != nil {
			_ = realtimeWriteError(conn, runErr.Error())
			return
		}
		summaries = append(summaries, summary)
		if controlledDisconnectAfterTurn != nil && turn.TurnIndex == *controlledDisconnectAfterTurn {
			// This is an opt-in recovery experiment. Close the underlying transport
			// without a WebSocket close frame so the client must observe a failure.
			_ = conn.UnderlyingConn().Close()
			return
		}
	}
	_ = realtimeWriteJSON(conn, map[string]any{
		"type":        "session_summary",
		"session_id":  plan.SessionID,
		"turns":       len(summaries),
		"protocol_ok": allRealtimeTurnsOK(summaries),
		"complete_us": nowMicros(),
	})
}

func realtimeControlledDisconnectAfterTurn(r *http.Request) (*int, error) {
	raw := r.URL.Query().Get("controlled_disconnect_after_turn")
	if raw == "" {
		return nil, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 0 || value >= realtimeMaxTurns {
		return nil, fmt.Errorf("invalid controlled_disconnect_after_turn")
	}
	return &value, nil
}

func realtimeRunTurn(
	r *http.Request,
	conn *websocket.Conn,
	incoming <-chan realtimeInbound,
	readErrors <-chan error,
	plan realtimeSessionPlan,
	turn realtimeTurnPlan,
) (realtimeTurnSummary, error) {
	summary := realtimeTurnSummary{
		Type:                  "turn_summary",
		TurnID:                turn.TurnID,
		TurnIndex:             turn.TurnIndex,
		UplinkFramesExpected:  turn.UplinkFrames,
		DownlinkFramesPlanned: turn.PlannedDownlinkFrames,
	}
	control, err := realtimeNextControl(r, conn, incoming, readErrors)
	if err != nil {
		return summary, err
	}
	if control.Type != "turn_start" || control.TurnID != turn.TurnID || control.TurnIndex != turn.TurnIndex {
		return summary, fmt.Errorf("unexpected turn_start")
	}

	for summary.UplinkFramesReceived < turn.UplinkFrames {
		message, waitErr := realtimeNextInbound(r, incoming, readErrors)
		if waitErr != nil {
			return summary, waitErr
		}
		if message.messageType == websocket.TextMessage {
			if handled, controlErr := realtimeHandleAuxControl(conn, message.data); handled {
				if controlErr != nil {
					return summary, controlErr
				}
				continue
			}
			return summary, fmt.Errorf("unexpected control while receiving uplink")
		}
		frameTurn, seq, payloadBytes, parseErr := decodeRealtimeUplink(message.data)
		if parseErr != nil || frameTurn != turn.TurnIndex || seq != summary.UplinkFramesReceived || payloadBytes != turn.UplinkFrameBytes {
			return summary, fmt.Errorf("invalid uplink frame")
		}
		summary.UplinkFramesReceived++
	}
	control, err = realtimeNextControl(r, conn, incoming, readErrors)
	if err != nil {
		return summary, err
	}
	if control.Type != "speech_commit" || control.TurnID != turn.TurnID {
		return summary, fmt.Errorf("expected speech_commit")
	}
	summary.CommitRecvUs = nowMicros()
	responseDeadline := time.Now().Add(durationMillis(turn.ResponseWaitMs))
	if err := realtimeWaitQuiet(r, conn, incoming, readErrors, responseDeadline); err != nil {
		return summary, err
	}

	frameDuration := time.Duration(plan.FrameMs) * time.Millisecond
	firstTarget := responseDeadline
	for seq := 0; seq < turn.PlannedDownlinkFrames; seq++ {
		target := firstTarget.Add(time.Duration(seq) * frameDuration)
		barge, waitErr := realtimeWaitForFrameOrBarge(r, conn, incoming, readErrors, target, turn)
		if waitErr != nil {
			return summary, waitErr
		}
		if barge != nil {
			summary.BargeInReceived = true
			summary.BargeInRecvUs = nowMicros()
			summary.StopAckUs = summary.BargeInRecvUs
			break
		}
		schedUs := nowMicros()
		if seq == 0 {
			summary.FirstDownlinkSchedUs = schedUs
		}
		frame := encodeRealtimeDownlink(turn.TurnIndex, seq, schedUs, turn.DownlinkFrameBytes, plan.Seed)
		if seq == 0 {
			summary.FirstDownlinkPreWriteUs = nowMicros()
		}
		if err := realtimeWriteBinary(conn, frame); err != nil {
			return summary, err
		}
		summary.DownlinkFramesEmitted++
	}
	summary.ProtocolOK = summary.UplinkFramesReceived == turn.UplinkFrames &&
		(summary.DownlinkFramesEmitted == turn.PlannedDownlinkFrames || (turn.Interrupted && summary.BargeInReceived)) &&
		(!turn.Interrupted || summary.BargeInReceived)
	if err := realtimeWriteJSON(conn, summary); err != nil {
		return summary, err
	}
	return summary, nil
}

func decodeRealtimePlan(encoded []byte) (realtimeSessionPlan, error) {
	var plan realtimeSessionPlan
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&plan); err != nil {
		return plan, fmt.Errorf("decode session plan: %w", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return plan, fmt.Errorf("session plan contains trailing JSON data")
	}
	if err := validateRealtimePlan(plan); err != nil {
		return plan, err
	}
	return plan, nil
}

func validateRealtimePlan(plan realtimeSessionPlan) error {
	if plan.ContractVersion != realtimeSessionContract {
		return fmt.Errorf("unsupported contract_version")
	}
	if plan.SessionID == "" || len(plan.SessionID) > 128 || plan.FrameMs < 10 || plan.FrameMs > 100 || plan.SetupMs < 0 || plan.SetupMs > 5_000 {
		return fmt.Errorf("invalid session identity or timing")
	}
	if len(plan.Turns) == 0 || len(plan.Turns) > realtimeMaxTurns {
		return fmt.Errorf("invalid turn count")
	}
	for index, turn := range plan.Turns {
		if turn.TurnIndex != index || turn.TurnID == "" || len(turn.TurnID) > 160 {
			return fmt.Errorf("invalid turn identity at %d", index)
		}
		if turn.UplinkFrames <= 0 || turn.UplinkFrames > realtimeMaxFrames || turn.PlannedDownlinkFrames <= 0 || turn.PlannedDownlinkFrames > realtimeMaxFrames {
			return fmt.Errorf("invalid frame count at %d", index)
		}
		if turn.UplinkFrameBytes <= 0 || turn.UplinkFrameBytes > realtimeMaxFrameBytes || turn.DownlinkFrameBytes <= 0 || turn.DownlinkFrameBytes > realtimeMaxFrameBytes {
			return fmt.Errorf("invalid frame size at %d", index)
		}
		if turn.ResponseWaitMs < 0 || turn.ResponseWaitMs > 5_000 {
			return fmt.Errorf("invalid response wait at %d", index)
		}
		if turn.StartAfterPreviousMs < 0 || turn.StartAfterPreviousMs > 10_000 {
			return fmt.Errorf("invalid inter-turn wait at %d", index)
		}
		if turn.Interrupted {
			if turn.BargeInAfterFrames == nil || *turn.BargeInAfterFrames <= 0 || *turn.BargeInAfterFrames >= turn.PlannedDownlinkFrames || turn.ExpectedStopWithinMs == nil || *turn.ExpectedStopWithinMs <= 0 || *turn.ExpectedStopWithinMs > 2_000 {
				return fmt.Errorf("invalid barge-in plan at %d", index)
			}
		} else if turn.BargeInAfterFrames != nil || turn.ExpectedStopWithinMs != nil {
			return fmt.Errorf("unexpected barge-in fields at %d", index)
		}
	}
	return nil
}

func realtimeNextControl(r *http.Request, conn *websocket.Conn, incoming <-chan realtimeInbound, readErrors <-chan error) (realtimeControl, error) {
	for {
		message, err := realtimeNextInbound(r, incoming, readErrors)
		if err != nil {
			return realtimeControl{}, err
		}
		if message.messageType != websocket.TextMessage {
			return realtimeControl{}, fmt.Errorf("expected text control")
		}
		if handled, handleErr := realtimeHandleAuxControl(conn, message.data); handled {
			if handleErr != nil {
				return realtimeControl{}, handleErr
			}
			continue
		}
		return decodeRealtimeControl(message.data)
	}
}

func realtimeHandleAuxControl(conn *websocket.Conn, encoded []byte) (bool, error) {
	control, err := decodeRealtimeControl(encoded)
	if err != nil {
		return false, err
	}
	if control.Type != "ping" {
		return false, nil
	}
	t1 := nowMicros()
	return true, realtimeWriteJSON(conn, map[string]any{
		"type":           "pong",
		"ping_id":        control.PingID,
		"client_mono_us": control.ClientMonoUs,
		"t1_us":          t1,
		"t2_us":          nowMicros(),
	})
}

func decodeRealtimeControl(encoded []byte) (realtimeControl, error) {
	var control realtimeControl
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&control); err != nil {
		return control, fmt.Errorf("decode control: %w", err)
	}
	return control, nil
}

func realtimeNextInbound(r *http.Request, incoming <-chan realtimeInbound, readErrors <-chan error) (realtimeInbound, error) {
	select {
	case <-r.Context().Done():
		return realtimeInbound{}, r.Context().Err()
	case err := <-readErrors:
		return realtimeInbound{}, err
	case message := <-incoming:
		return message, nil
	}
}

func realtimeWaitQuiet(r *http.Request, conn *websocket.Conn, incoming <-chan realtimeInbound, readErrors <-chan error, deadline time.Time) error {
	for {
		delay := time.Until(deadline)
		if delay <= 0 {
			return nil
		}
		timer := time.NewTimer(delay)
		select {
		case <-r.Context().Done():
			timer.Stop()
			return r.Context().Err()
		case err := <-readErrors:
			timer.Stop()
			return err
		case message := <-incoming:
			timer.Stop()
			if message.messageType != websocket.TextMessage {
				return fmt.Errorf("unexpected binary frame while waiting")
			}
			handled, err := realtimeHandleAuxControl(conn, message.data)
			if err != nil {
				return err
			}
			if !handled {
				return fmt.Errorf("unexpected control while waiting")
			}
		case <-timer.C:
			return nil
		}
	}
}

func realtimeWaitForFrameOrBarge(r *http.Request, conn *websocket.Conn, incoming <-chan realtimeInbound, readErrors <-chan error, deadline time.Time, turn realtimeTurnPlan) (*realtimeControl, error) {
	for {
		// A delayed scheduler can leave several absolute frame deadlines in the
		// past. Always consume an already-queued control before deciding that an
		// overdue frame may be emitted; otherwise a queued barge-in is skipped
		// while the server sends a burst of catch-up frames.
		select {
		case <-r.Context().Done():
			return nil, r.Context().Err()
		case err := <-readErrors:
			return nil, err
		case message := <-incoming:
			control, err := realtimeHandleDownlinkInbound(conn, message, turn)
			if err != nil || control != nil {
				return control, err
			}
			continue
		default:
		}

		delay := time.Until(deadline)
		if delay <= 0 {
			return nil, nil
		}
		timer := time.NewTimer(delay)
		select {
		case <-r.Context().Done():
			timer.Stop()
			return nil, r.Context().Err()
		case err := <-readErrors:
			timer.Stop()
			return nil, err
		case message := <-incoming:
			timer.Stop()
			control, err := realtimeHandleDownlinkInbound(conn, message, turn)
			if err != nil || control != nil {
				return control, err
			}
			continue
		case <-timer.C:
			// Loop once more so an inbound control that became ready with the
			// timer wins before the overdue-frame decision above.
			continue
		}
	}
}

func realtimeHandleDownlinkInbound(conn *websocket.Conn, message realtimeInbound, turn realtimeTurnPlan) (*realtimeControl, error) {
	if message.messageType != websocket.TextMessage {
		return nil, fmt.Errorf("unexpected binary frame during downlink")
	}
	control, err := decodeRealtimeControl(message.data)
	if err != nil {
		return nil, err
	}
	if control.Type == "ping" {
		if _, err := realtimeHandleAuxControl(conn, message.data); err != nil {
			return nil, err
		}
		return nil, nil
	}
	if control.Type != "barge_in" || !turn.Interrupted || control.TurnID != turn.TurnID {
		return nil, fmt.Errorf("unexpected control during downlink")
	}
	return &control, nil
}

func decodeRealtimeUplink(frame []byte) (turn int, seq int, payloadBytes int, err error) {
	if len(frame) < 10 || string(frame[:4]) != "ANEU" {
		return 0, 0, 0, fmt.Errorf("bad uplink header")
	}
	turn = int(binary.BigEndian.Uint16(frame[4:6]))
	seq = int(binary.BigEndian.Uint32(frame[6:10]))
	return turn, seq, len(frame) - 10, nil
}

func encodeRealtimeDownlink(turn, seq int, schedUs int64, payloadBytes int, seed int64) []byte {
	frame := make([]byte, 18+payloadBytes)
	copy(frame[:4], "ANED")
	binary.BigEndian.PutUint16(frame[4:6], uint16(turn))
	binary.BigEndian.PutUint32(frame[6:10], uint32(seq))
	binary.BigEndian.PutUint64(frame[10:18], uint64(schedUs))
	fillPayload(frame[18:], seed+int64(turn), seq)
	return frame
}

func realtimeWriteJSON(conn *websocket.Conn, value any) error {
	conn.SetWriteDeadline(time.Now().Add(realtimeWriteTimeout))
	return conn.WriteJSON(value)
}

func realtimeWriteBinary(conn *websocket.Conn, value []byte) error {
	conn.SetWriteDeadline(time.Now().Add(realtimeWriteTimeout))
	return conn.WriteMessage(websocket.BinaryMessage, value)
}

func realtimeWriteError(conn *websocket.Conn, message string) error {
	return realtimeWriteJSON(conn, map[string]any{"type": "error", "message": message})
}

func durationMillis(value float64) time.Duration {
	return time.Duration(value * float64(time.Millisecond))
}

func allRealtimeTurnsOK(summaries []realtimeTurnSummary) bool {
	for _, summary := range summaries {
		if !summary.ProtocolOK {
			return false
		}
	}
	return true
}
