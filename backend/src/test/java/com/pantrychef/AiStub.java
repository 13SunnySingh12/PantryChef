package com.pantrychef;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * In-process stand-in for the FastAPI service: records each request per path and returns a canned answer. Like
 * uvicorn, it must never see an HTTP/2 upgrade attempt, so those are recorded too.
 */
public final class AiStub {

	public final HttpServer server;

	public final List<String> tokens = new CopyOnWriteArrayList<>();

	public final List<String> upgrades = new CopyOnWriteArrayList<>();

	private final Map<String, List<String>> requests = new ConcurrentHashMap<>();

	private final Map<String, Object[]> answers = new ConcurrentHashMap<>();

	public volatile long delayMillis;

	public AiStub() {
		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		this.server.createContext("/", (exchange) -> {
			String path = exchange.getRequestURI().getPath();
			requests(path).add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			this.tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Service-Token")));
			if (exchange.getRequestHeaders().containsKey("Upgrade")) {
				this.upgrades.add(exchange.getRequestHeaders().getFirst("Upgrade"));
			}
			try {
				Thread.sleep(this.delayMillis);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			Object[] answer = this.answers.getOrDefault(path, new Object[] { 404, "{}" });
			byte[] bytes = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders((Integer) answer[0], bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		this.server.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	public void answer(String path, int status, String body) {
		this.answers.put(path, new Object[] { status, body });
	}

	public List<String> requests(String path) {
		return this.requests.computeIfAbsent(path, (p) -> new CopyOnWriteArrayList<>());
	}

	public void reset() {
		this.requests.clear();
		this.answers.clear();
		this.tokens.clear();
		this.upgrades.clear();
		this.delayMillis = 0;
	}

}
