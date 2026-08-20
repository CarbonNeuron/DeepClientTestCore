package main

import (
	"reflect"
	"testing"
	"time"
)

func TestParseImages(t *testing.T) {
	got := parseImages("1.21.1=image:a, 1.20.4=image:b,invalid")
	want := map[string]string{"1.21.1": "image:a", "1.20.4": "image:b"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parseImages() = %#v, want %#v", got, want)
	}
}

func TestSessionExpired(t *testing.T) {
	now := time.Now()
	if !sessionExpired(&session{LastActive: now.Add(-2 * time.Minute)}, now, time.Minute) {
		t.Fatal("inactive session should expire")
	}
	if sessionExpired(&session{LastActive: now.Add(-2 * time.Minute), Active: 1}, now, time.Minute) {
		t.Fatal("active proxied connection must prevent expiration")
	}
}

func TestBeginUseRejectsDeletingSession(t *testing.T) {
	s := &session{ID: "test", Deleting: true}
	m := &manager{sessions: map[string]*session{"test": s}}
	if m.beginUse(s) {
		t.Fatal("a deleting session accepted a new proxied request")
	}
	s.Deleting = false
	if !m.beginUse(s) {
		t.Fatal("an available session rejected a proxied request")
	}
	m.endUse(s)
	if s.Active != 0 {
		t.Fatalf("active connections = %d, want 0", s.Active)
	}
}

func TestSecureEqual(t *testing.T) {
	if !secureEqual("token", "token") || secureEqual("token", "other") || secureEqual("x", "xx") {
		t.Fatal("secureEqual returned an incorrect result")
	}
}
