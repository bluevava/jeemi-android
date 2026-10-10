//go:build !android && !linux

package mobile

import "fmt"

// AndroidTunnelActive is supported only by the Android/Linux runtime driver.
func AndroidTunnelActive(descriptor int) (bool, error) {
	return false, fmt.Errorf("tunnel_status_unavailable")
}
