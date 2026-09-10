package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"golang.org/x/net/proxy"
	"jeemi-android/engine/internal/config/dnstool"
	"jeemi-android/engine/internal/dnsquery"
	"net"
	"sync"
	"time"
)

// PrepareDNSDiagnostics adds private authenticated listeners after Android
// strips source listeners. The returned session never enters the library.
func PrepareDNSDiagnostics(configuration, secret string) (string, error) {
	if len(configuration) > maxBytes {
		return "", fmt.Errorf("size_limit")
	}
	config, session, err := dnstool.Prepare([]byte(configuration), secret)
	if err != nil {
		return "", fmt.Errorf("dns_session_failed")
	}
	result, _ := json.Marshal(struct {
		Configuration string          `json:"configuration"`
		Session       dnstool.Session `json:"session"`
	}{string(config), session})
	return string(result), nil
}

type dnsInput struct {
	TimeoutMS  int64            `json:"timeoutMS"`
	Request    dnsquery.Request `json:"request"`
	Session    *dnstool.Session `json:"session"`
	ProxyNode  string           `json:"proxyNode"`
	ProxyError string           `json:"proxyError"`
}
type DNSQuery struct {
	input  dnsInput
	ctx    context.Context
	cancel context.CancelFunc
}

func NewDNSQuery(raw string) (*DNSQuery, error) {
	var input dnsInput
	if len(raw) > 32768 || json.Unmarshal([]byte(raw), &input) != nil {
		return nil, fmt.Errorf("invalid_request")
	}
	domain, err := dnsquery.Domain(input.Request.Domain)
	if err != nil {
		return nil, err
	}
	input.Request.Domain = domain
	timeout := dnsquery.Timeout
	if input.TimeoutMS > 0 && input.TimeoutMS < 12000 {
		timeout = time.Duration(input.TimeoutMS) * time.Millisecond
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	return &DNSQuery{input, ctx, cancel}, nil
}
func (q *DNSQuery) Cancel() { q.cancel() }
func (q *DNSQuery) Run() string {
	defer q.cancel()
	input := q.input
	response := dnsquery.Response{Domain: input.Request.Domain, QueriedAt: time.Now().UTC().Format(time.RFC3339Nano)}
	run := func(server string, useProxy bool) dnsquery.Result {
		route := "direct"
		if useProxy {
			route = "proxy"
		}
		if _, _, err := dnsquery.Server(server); err != nil {
			return dnsquery.Failed(server, route, "invalid_server")
		}
		var dial dnsquery.DialContext
		if useProxy && (input.Session == nil || input.ProxyError != "" || input.ProxyNode == "") {
			return dnsquery.Failed(server, route, "proxy_invalid")
		}
		if s := input.Session; s != nil {
			address := s.DirectAddress
			if useProxy {
				address = s.ProxyAddress
			}
			host, _, err := net.SplitHostPort(address)
			if err != nil || host != "127.0.0.1" || len(s.Password) < 32 {
				return dnsquery.Failed(server, route, "query_failed")
			}
			socks, err := proxy.SOCKS5("tcp", address, &proxy.Auth{User: s.Username, Password: s.Password}, &net.Dialer{Timeout: 3 * time.Second})
			if err != nil {
				return dnsquery.Failed(server, route, "query_failed")
			}
			contextual, ok := socks.(proxy.ContextDialer)
			if !ok {
				return dnsquery.Failed(server, route, "query_failed")
			}
			dial = contextual.DialContext
		}
		result := dnsquery.Query(q.ctx, input.Request.Domain, server, route, dial)
		if useProxy {
			result.Selector = input.Session.MatchTarget
			result.Node = input.ProxyNode
		}
		return result
	}
	var wait sync.WaitGroup
	wait.Add(3)
	go func() { defer wait.Done(); response.Proxy = run(input.Request.ProxyDNS, true) }()
	go func() { defer wait.Done(); response.Direct = run(input.Request.DirectDNS, false) }()
	go func() { defer wait.Done(); response.Custom = run(input.Request.CustomDNS, input.Request.CustomProxy) }()
	wait.Wait()
	raw, _ := json.Marshal(response)
	return string(raw)
}
