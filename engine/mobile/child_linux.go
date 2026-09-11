//go:build android || linux

package mobile

import (
	"fmt"
	"io"
	"os"
	"runtime"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

// os/exec probes pidfd_open even when the caller only needs a child PID.
// Android before 12 can trap that syscall. Go's c-shared runtime does not
// install a SIGSYS handler, so the probe can terminate the entire app.
// ForkExec/Wait4 use the older process APIs without changing host signal handlers.
type forkCoreChild struct {
	pid        int
	mutex      sync.Mutex
	reaped     bool
	output     *os.File
	outputDone chan struct{}
}

func startCoreChild(executable string, arguments, environment []string, tun *os.File, output io.Writer) (coreChild, error) {
	null, err := os.OpenFile(os.DevNull, os.O_RDWR, 0)
	if err != nil {
		return nil, err
	}
	defer null.Close()
	reader, writer, err := os.Pipe()
	if err != nil {
		return nil, err
	}
	defer writer.Close()
	pid, err := syscall.ForkExec(executable, append([]string{executable}, arguments...), &syscall.ProcAttr{
		Env:   environment,
		Files: []uintptr{null.Fd(), writer.Fd(), null.Fd(), tun.Fd()},
		Sys:   &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL},
	})
	runtime.KeepAlive(tun)
	if err != nil {
		reader.Close()
		return nil, err
	}
	child := &forkCoreChild{pid: pid, output: reader, outputDone: make(chan struct{})}
	go func() {
		defer close(child.outputDone)
		defer reader.Close()
		_, _ = io.Copy(output, reader)
	}()
	return child, nil
}

func (p *forkCoreChild) Wait() error {
	// WNOWAIT observes exit while keeping the PID reserved. Reaping and
	// signalling then share one lock, so Stop cannot signal a recycled PID.
	// Linux siginfo_t is 128 bytes on both supported Android ABIs.
	var info [16]uint64
	var waitErr syscall.Errno
	for {
		_, _, waitErr = syscall.Syscall6(syscall.SYS_WAITID, 1 /* P_PID */, uintptr(p.pid),
			uintptr(unsafe.Pointer(&info[0])), syscall.WEXITED|syscall.WNOWAIT, 0, 0)
		if waitErr != syscall.EINTR {
			break
		}
	}
	var status syscall.WaitStatus
	var err error
	for {
		p.mutex.Lock()
		var waited int
		// A failed waitid must not cause a blocking wait under the signal lock.
		flags := 0
		if waitErr != 0 {
			flags = syscall.WNOHANG
		}
		waited, err = syscall.Wait4(p.pid, &status, flags, nil)
		if waited == p.pid || (err != nil && err != syscall.EINTR) {
			p.reaped = true
		}
		done := p.reaped
		p.mutex.Unlock()
		if done {
			break
		}
		if err != syscall.EINTR {
			time.Sleep(10 * time.Millisecond)
		}
	}
	// Usually EOF is immediate. Bound draining if a descendant inherited stdout.
	timer := time.NewTimer(time.Second)
	defer timer.Stop()
	select {
	case <-p.outputDone:
	case <-timer.C:
		_ = p.output.Close()
		<-p.outputDone
	}
	if err != nil {
		return err
	}
	if status.Exited() && status.ExitStatus() == 0 {
		return nil
	}
	return fmt.Errorf("core_child_exit: %d", int(status))
}

func (p *forkCoreChild) Signal(signal os.Signal) error {
	value, ok := signal.(syscall.Signal)
	if !ok {
		return fmt.Errorf("invalid_child_signal")
	}
	p.mutex.Lock()
	defer p.mutex.Unlock()
	if p.reaped {
		return os.ErrProcessDone
	}
	return syscall.Kill(p.pid, value)
}

func (p *forkCoreChild) Kill() error { return p.Signal(syscall.SIGKILL) }
