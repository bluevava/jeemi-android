//go:build !android && !linux

package mobile

import (
	"fmt"
	"io"
)

func protectionServer(SocketProtector) (io.Closer, string, string, error) {
	return nil, "", "", fmt.Errorf("socket protection is only available on Android/Linux")
}
