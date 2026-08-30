package main

import "sync/atomic"

// reserveSessionSlot atomically reserves one of the configured session slots.
// A separate Load-then-Add is unsafe under concurrent requests because several
// callers can observe the same available slot and all proceed.
func (g *Gateway) reserveSessionSlot() bool {
	limit := int64(g.cfg.MaxSessions)
	if limit <= 0 {
		return false
	}
	for {
		current := g.sessionCount.Load()
		if current >= limit {
			return false
		}
		if g.sessionCount.CompareAndSwap(current, current+1) {
			return true
		}
	}
}

// releaseSessionSlot releases a slot previously reserved by
// reserveSessionSlot. It is deliberately CAS-based so a defensive release
// cannot drive the counter below zero.
func (g *Gateway) releaseSessionSlot() {
	for {
		current := g.sessionCount.Load()
		if current <= 0 {
			return
		}
		if atomic.CompareAndSwapInt64((*int64)(&g.sessionCount), current, current-1) {
			return
		}
	}
}
