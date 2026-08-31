package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"
)

func TestLoadGatewayServerTLSConfigRequiresAllInputs(t *testing.T) {
	if _, err := LoadGatewayServerTLSConfig("", "", ""); err == nil {
		t.Fatal("expected missing TLS configuration to fail closed")
	}
}

func TestLoadGatewayServerTLSConfigEnforcesMutualTLS(t *testing.T) {
	dir := t.TempDir()
	caCert, caKey := makeTLSFixtureCertificate(t, "gateway-ca", true, nil, nil)
	serverCert, serverKey := makeTLSFixtureCertificate(t, "gateway-server", false, caCert, caKey)
	clientCert, clientKey := makeTLSFixtureCertificate(t, "device-a", false, caCert, caKey)

	caPath := writePEMFile(t, dir+"/ca.pem", "CERTIFICATE", caCert)
	serverCertPath := writePEMFile(t, dir+"/server.pem", "CERTIFICATE", serverCert)
	serverKeyPath := writePEMFile(t, dir+"/server-key.pem", "EC PRIVATE KEY", serverKey)
	clientCertPath := writePEMFile(t, dir+"/client.pem", "CERTIFICATE", clientCert)
	clientKeyPath := writePEMFile(t, dir+"/client-key.pem", "EC PRIVATE KEY", clientKey)

	cfg, err := LoadGatewayServerTLSConfig(serverCertPath, serverKeyPath, caPath)
	if err != nil {
		t.Fatalf("LoadGatewayServerTLSConfig: %v", err)
	}
	if cfg.MinVersion != tls.VersionTLS13 {
		t.Fatalf("MinVersion = %d, want TLS 1.3", cfg.MinVersion)
	}
	if cfg.ClientAuth != tls.RequireAndVerifyClientCert {
		t.Fatalf("ClientAuth = %v, want RequireAndVerifyClientCert", cfg.ClientAuth)
	}
	if cfg.ClientCAs == nil {
		t.Fatal("ClientCAs is nil")
	}

	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	server.TLS = cfg.Clone()
	server.StartTLS()
	defer server.Close()

	clientKeyPair, err := tls.LoadX509KeyPair(clientCertPath, clientKeyPath)
	if err != nil {
		t.Fatalf("load client key pair: %v", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(mustReadFile(t, caPath)) {
		t.Fatal("failed to load test CA")
	}

	// Verify the certificate by its DNS SAN rather than coupling the test to
	// httptest's loopback URL. The server certificate is deliberately issued
	// for gateway-server, while the dial target remains the ephemeral test
	// listener. This proves certificate verification independently of address
	// selection and avoids a false failure when the test listener is 127.0.0.1.
	clientTLS := &tls.Config{
		MinVersion:   tls.VersionTLS13,
		RootCAs:      pool,
		Certificates: []tls.Certificate{clientKeyPair},
		ServerName:   "gateway-server",
	}
	client := &http.Client{Transport: &http.Transport{
		TLSClientConfig: clientTLS,
		DialContext: func(_ net.Context, _, _ string) (net.Conn, error) {
			return net.Dial("tcp", server.Listener.Addr().String())
		},
	}}
	resp, err := client.Get("https://gateway-server/")
	if err != nil {
		t.Fatalf("mTLS request failed: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusNoContent)
	}

	noCertClient := &http.Client{Transport: &http.Transport{
		TLSClientConfig: &tls.Config{
			MinVersion: tls.VersionTLS13,
			RootCAs:    pool,
			ServerName: "gateway-server",
		},
		DialContext: func(_ net.Context, _, _ string) (net.Conn, error) {
			return net.Dial("tcp", server.Listener.Addr().String())
		},
	}}
	resp, err = noCertClient.Get("https://gateway-server/")
	if err == nil {
		resp.Body.Close()
		t.Fatal("expected client without certificate to be rejected")
	}
}

func makeTLSFixtureCertificate(t *testing.T, commonName string, isCA bool, parentDER, parentKeyPEM []byte) ([]byte, []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		t.Fatal(err)
	}
	keyUsage := x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment
	if isCA {
		keyUsage = x509.KeyUsageCertSign | x509.KeyUsageCRLSign
	}
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: commonName},
		NotBefore:             time.Now().Add(-time.Minute),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              keyUsage,
		BasicConstraintsValid: true,
		IsCA:                  isCA,
	}
	if isCA {
		tmpl.MaxPathLen = 1
	} else {
		tmpl.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth, x509.ExtKeyUsageServerAuth}
	}
	if commonName == "gateway-server" {
		tmpl.DNSNames = []string{"gateway-server"}
		tmpl.IPAddresses = []net.IP{net.ParseIP("127.0.0.1")}
	}
	var parent *x509.Certificate
	signerKey := key
	if len(parentDER) > 0 {
		parent, err = x509.ParseCertificate(parentDER)
		if err != nil {
			t.Fatal(err)
		}
		block, _ := pem.Decode(parentKeyPEM)
		if block == nil {
			t.Fatal("invalid parent key PEM")
		}
		signerKey, err = x509.ParseECPrivateKey(block.Bytes)
		if err != nil {
			t.Fatal(err)
		}
	} else {
		parent = tmpl
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, parent, &key.PublicKey, signerKey)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return der, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
}

func writePEMFile(t *testing.T, path, typ string, der []byte) string {
	t.Helper()
	if typ == "EC PRIVATE KEY" {
		if err := os.WriteFile(path, der, 0600); err != nil {
			t.Fatal(err)
		}
		return path
	}
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: typ, Bytes: der}), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

func mustReadFile(t *testing.T, path string) []byte {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
