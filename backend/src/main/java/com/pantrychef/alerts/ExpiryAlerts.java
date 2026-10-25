package com.pantrychef.alerts;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pantrychef.pantry.Expiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app expiry alerts (FR-006–FR-008, FR-023, A-01, A-02). One reconciliation, run daily for every item and
 * immediately for an item after any pantry change, keeps at most one open alert per item: {@code expiring_soon}
 * or {@code expired} while the item has stock and an expiry date in the window, otherwise none. Re-running it is
 * a no-op, and a replaced or no-longer-relevant alert is resolved (kept as history), never deleted.
 */
@Service
public class ExpiryAlerts {

	private static final Logger log = LoggerFactory.getLogger(ExpiryAlerts.class);

	private static final String TYPE = "CASE WHEN i.expiry_date < :today THEN 'expired' ELSE 'expiring_soon' END";

	private static final String IN_WINDOW = "i.deleted_at IS NULL AND i.quantity_remaining > 0"
			+ " AND i.expiry_date IS NOT NULL AND i.expiry_date <= :soon";

	private final JdbcClient db;

	private final Clock clock;

	private final ZoneId zone;

	public ExpiryAlerts(JdbcClient db, Clock clock, @Value("${pantrychef.timezone}") ZoneId zone) {
		this.db = db;
		this.clock = clock;
		this.zone = zone;
	}

	public record AlertItem(UUID id, String name, BigDecimal quantityRemaining, String unit, LocalDate expiryDate,
			Long daysUntilExpiry) {
	}

	public record Alert(UUID id, String type, LocalDate alertDate, AlertItem item) {
	}

	public record ScanResult(int resolved, int created) {
	}

	/** Today's calendar date in APP_TIMEZONE; the date every expiry rule is evaluated against. */
	public LocalDate today() {
		return LocalDate.now(this.clock.withZone(this.zone));
	}

	/** Daily at 00:05 in APP_TIMEZONE, and once at startup in case the app was off at that time. */
	@Scheduled(cron = "${pantrychef.expiry.cron:0 5 0 * * *}", zone = "${pantrychef.timezone}")
	@EventListener(ApplicationReadyEvent.class)
	public void scheduledScan() {
		try {
			LocalDate today = today();
			ScanResult result = scan(today);
			log.info("Expiry scan for {}: {} alerts resolved, {} created", today, result.resolved(), result.created());
		}
		catch (RuntimeException ex) {
			// Counts and failure category only: no user data in scheduler logs.
			log.error("Expiry scan failed: {}", ex.getClass().getName());
		}
	}

	@Transactional
	public ScanResult scan(LocalDate today) {
		return reconcile(today, null);
	}

	/** Brings one item's alert up to date; called in the same transaction as the pantry change. */
	public void reconcileItem(UUID itemId) {
		reconcile(today(), itemId);
	}

	public List<Alert> list(UUID user) {
		LocalDate today = today();
		return this.db
			.sql("SELECT a.id, a.alert_type, a.alert_date, i.id AS item_id, i.name, i.quantity_remaining, i.unit,"
					+ " i.expiry_date FROM expiry_alerts a JOIN pantry_items i ON i.id = a.pantry_item_id"
					+ " WHERE a.user_id = :user AND i.user_id = :user AND a.resolved_at IS NULL"
					+ " ORDER BY i.expiry_date, lower(i.name), i.id")
			.param("user", user)
			.query((rs, n) -> {
				LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
				return new Alert(rs.getObject("id", UUID.class), rs.getString("alert_type"),
						rs.getObject("alert_date", LocalDate.class),
						new AlertItem(rs.getObject("item_id", UUID.class), rs.getString("name"),
								rs.getBigDecimal("quantity_remaining"), rs.getString("unit"), expiry,
								Expiry.daysUntil(expiry, today)));
			})
			.list();
	}

	private ScanResult reconcile(LocalDate today, UUID itemId) {
		String onlyItem = itemId == null ? "" : " AND i.id = :item";
		LocalDate soon = today.plusDays(Expiry.SOON_DAYS);
		int resolved = this.db
			.sql("UPDATE expiry_alerts a SET resolved_at = now(), updated_at = now() FROM pantry_items i"
					+ " WHERE a.pantry_item_id = i.id AND a.resolved_at IS NULL AND (NOT (" + IN_WINDOW + ")"
					+ " OR a.alert_type <> " + TYPE + ")" + onlyItem)
			.params(params(today, soon, itemId))
			.update();
		this.db
			.sql("UPDATE expiry_alerts a SET alert_date = :today, updated_at = now() FROM pantry_items i"
					+ " WHERE a.pantry_item_id = i.id AND a.resolved_at IS NULL AND a.alert_date <> :today" + onlyItem)
			.params(params(today, soon, itemId))
			.update();
		int created = this.db
			.sql("INSERT INTO expiry_alerts (user_id, pantry_item_id, alert_type, alert_date)"
					+ " SELECT i.user_id, i.id, " + TYPE + ", :today FROM pantry_items i WHERE " + IN_WINDOW + onlyItem
					+ " ON CONFLICT (pantry_item_id) WHERE resolved_at IS NULL DO NOTHING")
			.params(params(today, soon, itemId))
			.update();
		return new ScanResult(resolved, created);
	}

	private static Map<String, Object> params(LocalDate today, LocalDate soon, UUID itemId) {
		Map<String, Object> p = new HashMap<>();
		p.put("today", today);
		p.put("soon", soon);
		if (itemId != null) {
			p.put("item", itemId);
		}
		return p;
	}

}
