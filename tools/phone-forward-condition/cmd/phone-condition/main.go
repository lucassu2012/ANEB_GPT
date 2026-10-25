package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"

	condition "aneb-phone-forward-condition"
)

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
}
func run(args []string, output io.Writer) error {
	if len(args) == 0 || (args[0] != "plan" && args[0] != "inspect" && args[0] != "limit" && args[0] != "_worker-inspect" && args[0] != "_worker-limit") {
		return errors.New("usage: phone-condition plan|inspect|limit -config <request.json>; execution requires a fixed, approved candidate")
	}
	flags := flag.NewFlagSet(args[0], flag.ContinueOnError)
	config := flags.String("config", "", "fixed request JSON path")
	requestSHA := flags.String("request-sha256", "", "private worker input binding")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	privateWorker := args[0] == "_worker-inspect" || args[0] == "_worker-limit"
	if *config == "" || flags.NArg() != 0 || privateWorker != (*requestSHA != "") {
		return errors.New("a fixed config and no extra arguments are required")
	}
	f, err := os.Open(*config)
	if err != nil {
		return err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, (64<<10)+1))
	if err != nil {
		return err
	}
	if args[0] == "inspect" {
		return executeInspection(data, output)
	}
	if args[0] == "limit" {
		return executeLimit(data, output)
	}
	if args[0] == "_worker-inspect" {
		return inspectWorker(data, *requestSHA)
	}
	if args[0] == "_worker-limit" {
		return limitWorker(data, *requestSHA)
	}
	request, plan, err := condition.ParseRequest(data)
	if err != nil {
		return err
	}
	hash := sha256.Sum256(data)
	return json.NewEncoder(output).Encode(struct {
		Format          string         `json:"format"`
		State           string         `json:"state"`
		Mode            condition.Mode `json:"mode"`
		Filter          string         `json:"filter"`
		RequestSHA256   string         `json:"request_sha256"`
		DriverOpened    bool           `json:"driver_opened"`
		TrafficCaptured bool           `json:"traffic_captured"`
	}{"phone-forward-plan-1", "NOT_RUN", request.Mode, plan.Filter, hex.EncodeToString(hash[:]), false, false})
}
