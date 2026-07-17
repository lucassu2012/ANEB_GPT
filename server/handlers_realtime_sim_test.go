package main

import (
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestRealtimeSimExecutesDuplexTurnAndBargeIn(t *testing.T) {
	server := httptest.NewServer((&app{}).routes())
	defer server.Close()
	url := "ws" + strings.TrimPrefix(server.URL, "http") + "/api/v1/realtime-sim"
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	plan := realtimeSessionPlan{
		ContractVersion: realtimeSessionContract,
		SessionID:       "session-test",
		Seed:            42,
		SetupMs:         1,
		FrameMs:         20,
		Turns: []realtimeTurnPlan{{
			TurnID:                "turn-test",
			TurnIndex:             0,
			StartAfterPreviousMs:  0,
			UplinkFrames:          2,
			UplinkFrameBytes:      32,
			ResponseWaitMs:        1,
			PlannedDownlinkFrames: 5,
			DownlinkFrameBytes:    48,
			Interrupted:           true,
			BargeInAfterFrames:    intPointer(2),
			ExpectedStopWithinMs:  intPointer(300),
		}},
	}
	if err := conn.WriteJSON(plan); err != nil {
		t.Fatal(err)
	}
	ready := readRealtimeTestControl(t, conn)
	if ready["type"] != "session_ready" {
		t.Fatalf("unexpected ready: %#v", ready)
	}
	if err := conn.WriteJSON(realtimeControl{Type: "turn_start", TurnID: "turn-test", TurnIndex: 0}); err != nil {
		t.Fatal(err)
	}
	for seq := 0; seq < 2; seq++ {
		if err := conn.WriteMessage(websocket.BinaryMessage, encodeRealtimeTestUplink(0, seq, 32)); err != nil {
			t.Fatal(err)
		}
	}
	if err := conn.WriteJSON(realtimeControl{Type: "speech_commit", TurnID: "turn-test", TurnIndex: 0}); err != nil {
		t.Fatal(err)
	}

	frames := 0
	var turnSummary map[string]any
	deadline := time.Now().Add(3 * time.Second)
	for turnSummary == nil && time.Now().Before(deadline) {
		messageType, data, readErr := conn.ReadMessage()
		if readErr != nil {
			t.Fatal(readErr)
		}
		if messageType == websocket.BinaryMessage {
			frames++
			if frames == 2 {
				if err := conn.WriteJSON(realtimeControl{Type: "barge_in", TurnID: "turn-test", TurnIndex: 0}); err != nil {
					t.Fatal(err)
				}
			}
			continue
		}
		var value map[string]any
		if err := json.Unmarshal(data, &value); err != nil {
			t.Fatal(err)
		}
		if value["type"] == "turn_summary" {
			turnSummary = value
		}
	}
	if turnSummary == nil || turnSummary["barge_in_received"] != true || turnSummary["protocol_ok"] != true {
		t.Fatalf("unexpected turn summary: %#v", turnSummary)
	}
	if frames != 2 {
		t.Fatalf("expected exactly two frames before stop, got %d", frames)
	}
	sessionSummary := readRealtimeTestControl(t, conn)
	if sessionSummary["type"] != "session_summary" || sessionSummary["protocol_ok"] != true {
		t.Fatalf("unexpected session summary: %#v", sessionSummary)
	}
}

func TestRealtimePlanRejectsNetworkOutcomes(t *testing.T) {
	plan := []byte(`{"contract_version":"aneb-realtime-session-v1","session_id":"x","seed":1,"setup_ms":1,"frame_ms":20,"packet_loss":0.1,"turns":[]}`)
	if _, err := decodeRealtimePlan(plan); err == nil || !strings.Contains(err.Error(), "unknown field") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestRealtimeControlledDisconnectClosesOnlyAfterCompletedTurn(t *testing.T) {
	server := httptest.NewServer((&app{}).routes())
	defer server.Close()
	url := "ws" + strings.TrimPrefix(server.URL, "http") +
		"/api/v1/realtime-sim?controlled_disconnect_after_turn=0"
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	plan := realtimeSessionPlan{
		ContractVersion: realtimeSessionContract,
		SessionID:       "controlled-recovery-test",
		Seed:            42,
		SetupMs:         1,
		FrameMs:         20,
		Turns: []realtimeTurnPlan{{
			TurnID:                "turn-before-failure",
			TurnIndex:             0,
			UplinkFrames:          1,
			UplinkFrameBytes:      32,
			ResponseWaitMs:        1,
			PlannedDownlinkFrames: 1,
			DownlinkFrameBytes:    48,
		}},
	}
	if err := conn.WriteJSON(plan); err != nil {
		t.Fatal(err)
	}
	if ready := readRealtimeTestControl(t, conn); ready["type"] != "session_ready" {
		t.Fatalf("unexpected ready: %#v", ready)
	}
	if err := conn.WriteJSON(realtimeControl{Type: "turn_start", TurnID: "turn-before-failure"}); err != nil {
		t.Fatal(err)
	}
	if err := conn.WriteMessage(websocket.BinaryMessage, encodeRealtimeTestUplink(0, 0, 32)); err != nil {
		t.Fatal(err)
	}
	if err := conn.WriteJSON(realtimeControl{Type: "speech_commit", TurnID: "turn-before-failure"}); err != nil {
		t.Fatal(err)
	}
	messageType, _, err := conn.ReadMessage()
	if err != nil || messageType != websocket.BinaryMessage {
		t.Fatalf("expected completed downlink frame before failure, type=%d err=%v", messageType, err)
	}
	if summary := readRealtimeTestControl(t, conn); summary["type"] != "turn_summary" || summary["protocol_ok"] != true {
		t.Fatalf("unexpected turn summary: %#v", summary)
	}
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, _, err := conn.ReadMessage(); err == nil {
		t.Fatal("expected abrupt transport close instead of session_summary")
	}
}

func TestRealtimeControlledDisconnectRejectsInvalidTurn(t *testing.T) {
	server := httptest.NewServer((&app{}).routes())
	defer server.Close()
	response, err := http.Get(server.URL + "/api/v1/realtime-sim?controlled_disconnect_after_turn=bad")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", response.StatusCode)
	}
}

func encodeRealtimeTestUplink(turn, seq, payloadBytes int) []byte {
	frame := make([]byte, 10+payloadBytes)
	copy(frame[:4], "ANEU")
	binary.BigEndian.PutUint16(frame[4:6], uint16(turn))
	binary.BigEndian.PutUint32(frame[6:10], uint32(seq))
	return frame
}

func readRealtimeTestControl(t *testing.T, conn *websocket.Conn) map[string]any {
	t.Helper()
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, encoded, err := conn.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	var value map[string]any
	if err := json.Unmarshal(encoded, &value); err != nil {
		t.Fatal(err)
	}
	return value
}

func intPointer(value int) *int { return &value }
