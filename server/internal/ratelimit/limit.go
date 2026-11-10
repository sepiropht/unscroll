package ratelimit

import (
	"sync"
	"time"
)

// Limiter is a sliding-window counter: limit events per window per key.
type Limiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration
	hits   map[string][]time.Time
}

// New allows limit events per minute for each key.
func New(perMinute int) *Limiter {
	return NewWindow(perMinute, time.Minute)
}

// NewWindow allows limit events per window for each key.
func NewWindow(limit int, window time.Duration) *Limiter {
	if limit < 1 {
		limit = 1
	}
	if window <= 0 {
		window = time.Minute
	}
	return &Limiter{
		limit:  limit,
		window: window,
		hits:   map[string][]time.Time{},
	}
}

// Allow records one hit when the key is under the limit.
func (l *Limiter) Allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	cutoff := now.Add(-l.window)
	prev := l.hits[key]
	kept := prev[:0]
	for _, t := range prev {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= l.limit {
		l.hits[key] = kept
		return false
	}
	l.hits[key] = append(kept, now)
	return true
}
