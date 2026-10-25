package com.pantrychef.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window counter: at most {@code max} recorded hits per key within {@code window}.
 * ponytail: in-memory and per instance (resets on restart); move to the database if the backend ever runs as
 * more than one instance.
 */
public class RateLimiter {

	private static final int PRUNE_THRESHOLD = 10_000;

	private final int max;

	private final Duration window;

	private final Clock clock;

	private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

	public RateLimiter(int max, Duration window, Clock clock) {
		this.max = max;
		this.window = window;
		this.clock = clock;
	}

	public boolean isLimited(String key) {
		Deque<Instant> times = this.hits.get(key);
		if (times == null) {
			return false;
		}
		synchronized (times) {
			dropExpired(times);
			return times.size() >= this.max;
		}
	}

	public void record(String key) {
		if (this.hits.size() > PRUNE_THRESHOLD) {
			this.hits.values().removeIf((times) -> {
				synchronized (times) {
					dropExpired(times);
					return times.isEmpty();
				}
			});
		}
		Deque<Instant> times = this.hits.computeIfAbsent(key, (k) -> new ArrayDeque<>());
		synchronized (times) {
			times.addLast(this.clock.instant());
		}
	}

	public void reset(String key) {
		this.hits.remove(key);
	}

	public Duration window() {
		return this.window;
	}

	private void dropExpired(Deque<Instant> times) {
		Instant cutoff = this.clock.instant().minus(this.window);
		while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
			times.pollFirst();
		}
	}

}
