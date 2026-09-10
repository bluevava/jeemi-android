//go:build !android && !linux

package mobile

import "os/exec"

func configureChild(command *exec.Cmd) {}
