//go:build !android && !linux

package mobile

import (
	"io"
	"os"
	"os/exec"
)

type execCoreChild struct{ command *exec.Cmd }

func startCoreChild(executable string, arguments, environment []string, tun *os.File, output io.Writer) (coreChild, error) {
	command := exec.Command(executable, arguments...)
	command.ExtraFiles = []*os.File{tun}
	command.Stdout = output
	command.Stderr = io.Discard
	command.Env = environment
	if err := command.Start(); err != nil {
		return nil, err
	}
	return &execCoreChild{command: command}, nil
}

func (p *execCoreChild) Wait() error                   { return p.command.Wait() }
func (p *execCoreChild) Signal(signal os.Signal) error { return p.command.Process.Signal(signal) }
func (p *execCoreChild) Kill() error                   { return p.command.Process.Kill() }
