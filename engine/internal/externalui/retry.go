package externalui

import (
	"context"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"time"
)

type transientFailure struct{ error }

func retryable(err error) bool {
	var transient transientFailure
	var unknown x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var hostname x509.HostnameError
	if errors.As(err, &unknown) || errors.As(err, &invalid) || errors.As(err, &hostname) {
		return false
	}
	var network net.Error
	return errors.As(err, &transient) || errors.As(err, &network) || errors.Is(err, io.ErrUnexpectedEOF)
}

// Retry entire requests, including interrupted bodies, within the caller's
// total deadline. A retry starts from zero; partial bytes are never installed.
func (m *Manager) retry(ctx context.Context, operation func() error) error {
	for attempt := 0; ; attempt++ {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := operation()
		if err == nil || attempt == 2 || !retryable(err) {
			return err
		}
		timer := time.NewTimer(m.retryDelay * time.Duration(1<<attempt))
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}

type contextReader struct {
	ctx   context.Context
	input io.Reader
}

func (r contextReader) Read(data []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.input.Read(data)
}
