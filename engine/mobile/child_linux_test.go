//go:build android || linux

package mobile

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"testing"
	"time"
)

func TestCoreChildHelperProcess(t *testing.T) {
	mode := os.Getenv("JEEMI_CORE_CHILD_TEST")
	if mode == "" {
		return
	}
	switch mode {
	case "fd-and-output":
		file := os.NewFile(3, "test-tun")
		_, _ = file.WriteString("fd-three\n")
		_ = file.Close()
		fmt.Println("ignored core log")
		fmt.Println("Start TUN listening error: synthetic test failure")
	case "interrupt":
		interrupt := make(chan os.Signal, 1)
		signal.Notify(interrupt, os.Interrupt)
		file := os.NewFile(3, "ready")
		_, _ = file.WriteString("ready\n")
		_ = file.Close()
		<-interrupt
	case "kill":
		file := os.NewFile(3, "ready")
		_, _ = file.WriteString("ready\n")
		_ = file.Close()
		for {
			time.Sleep(time.Hour)
		}
	default:
		os.Exit(2)
	}
	os.Exit(0)
}

// Keep the spawning thread alive, matching startCore's parent-death contract.
func launchChildForTest(t *testing.T, mode string, output io.Writer) (coreChild, *os.File, <-chan error) {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reader.Close() })
	started := make(chan coreChild)
	done := make(chan error, 1)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		child, err := startCoreChild(executable, []string{"-test.run=^TestCoreChildHelperProcess$"},
			append(os.Environ(), "JEEMI_CORE_CHILD_TEST="+mode), writer, output)
		_ = writer.Close()
		started <- child
		if err != nil {
			done <- err
			return
		}
		done <- child.Wait()
	}()
	child := <-started
	if child == nil {
		t.Fatal(<-done)
	}
	t.Cleanup(func() { _ = child.Kill() })
	return child, reader, done
}

func awaitChild(t *testing.T, done <-chan error) error {
	t.Helper()
	select {
	case err := <-done:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("child did not finish")
		return nil
	}
}

func TestCoreChildPreservesFDThreeAndDrainsOutput(t *testing.T) {
	status := &tunnelStatus{}
	child, reader, done := launchChildForTest(t, "fd-and-output", status)
	if err := awaitChild(t, done); err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(reader)
	if err != nil || string(data) != "fd-three\n" {
		t.Fatalf("descriptor 3: %q, %v", data, err)
	}
	if status.errorText != "Start TUN listening error: synthetic test failure" {
		t.Fatalf("final TUN error was not drained: %q", status.errorText)
	}
	if err := child.Kill(); !errors.Is(err, os.ErrProcessDone) {
		t.Fatalf("completed child is still signalable: %v", err)
	}
}

func TestCoreChildInterruptAndKillAreReaped(t *testing.T) {
	for _, mode := range []string{"interrupt", "kill"} {
		t.Run(mode, func(t *testing.T) {
			child, reader, done := launchChildForTest(t, mode, io.Discard)
			if err := reader.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
				t.Fatal(err)
			}
			ready, err := io.ReadAll(reader)
			if err != nil || string(ready) != "ready\n" {
				t.Fatalf("child readiness: %q, %v", ready, err)
			}
			if mode == "interrupt" {
				err = child.Signal(os.Interrupt)
			} else {
				err = child.Kill()
			}
			if err != nil {
				t.Fatal(err)
			}
			err = awaitChild(t, done)
			if (mode == "interrupt") != (err == nil) {
				t.Fatalf("unexpected exit: %v", err)
			}
			if err := child.Signal(os.Interrupt); !errors.Is(err, os.ErrProcessDone) {
				t.Fatalf("completed PID can still be signalled: %v", err)
			}
		})
	}
}

func TestCoreChildFailedExecLeavesCallerDescriptorOpen(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	defer writer.Close()
	for range 10 {
		_, err = startCoreChild("/jeemi-nonexistent-core", nil, nil, writer, &bytes.Buffer{})
		if !errors.Is(err, syscall.ENOENT) {
			t.Fatalf("unexpected exec failure: %v", err)
		}
	}
	if _, err := writer.WriteString("caller-owned"); err != nil {
		t.Fatalf("failed start closed caller descriptor: %v", err)
	}
	writer.Close()
	value, err := io.ReadAll(reader)
	if err != nil || string(value) != "caller-owned" {
		t.Fatalf("descriptor after failure: %q, %v", value, err)
	}
}
