package com.pantrychef.pantry;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pantrychef.alerts.ExpiryAlerts;
import com.pantrychef.web.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pantry stock and its history (FR-003–FR-005, A-03–A-05). Every query is scoped by the authenticated owner;
 * another user's item is indistinguishable from a missing one (404). Stock only changes through events or an
 * explicit quantity correction, and can never go below zero.
 */
@Service
public class PantryService {

	/** ponytail: fixed cap instead of pagination; add paging if a pantry ever needs more rows. */
	static final int LIST_LIMIT = 500;

	private static final String COLUMNS = "id, name, category, quantity_initial, quantity_remaining, unit, purchase_price,"
			+ " currency_code, purchase_date, expiry_date, version, created_at, updated_at";

	private final JdbcClient db;

	private final ExpiryAlerts alerts;

	private final String currency;

	public PantryService(JdbcClient db, ExpiryAlerts alerts, @Value("${pantrychef.currency}") String currency) {
		this.db = db;
		this.alerts = alerts;
		this.currency = currency;
	}

	public record Item(UUID id, String name, String category, BigDecimal quantity, BigDecimal quantityRemaining,
			String unit, BigDecimal purchasePrice, String currencyCode, LocalDate purchaseDate, LocalDate expiryDate,
			String expiryStatus, Long daysUntilExpiry, long version, Instant createdAt, Instant updatedAt) {
	}

	public record ItemInput(String name, String category, BigDecimal quantity, String unit, BigDecimal purchasePrice,
			LocalDate purchaseDate, LocalDate expiryDate) {
	}

	public record Event(UUID id, String type, BigDecimal quantity, String unit, Instant occurredAt, String notes) {
	}

	public record EventResult(Event event, Item item, boolean replayed) {
	}

	private LocalDate today() {
		return this.alerts.today();
	}

	/** What the user can cook with: not deleted, something left, not past its expiry date (A-01). */
	public record Usable(String name, Long daysToExpiry, BigDecimal quantity, String unit) {
	}

	public List<Usable> usableStock(UUID user) {
		return usableStock(user, today());
	}

	/** Stock still usable on {@code day} (not expired by then), with days to expiry counted from that day. */
	public List<Usable> usableStock(UUID user, LocalDate day) {
		return this.db
			.sql("SELECT name, expiry_date, quantity_remaining, unit FROM pantry_items WHERE user_id = ?"
					+ " AND deleted_at IS NULL AND quantity_remaining > 0 AND (expiry_date IS NULL OR expiry_date >= ?)"
					+ " ORDER BY id")
			.params(user, day)
			.query((rs, n) -> new Usable(rs.getString("name"),
					Expiry.daysUntil(rs.getObject("expiry_date", LocalDate.class), day),
					rs.getBigDecimal("quantity_remaining"), rs.getString("unit")))
			.list();
	}

	public List<Item> list(UUID user) {
		return this.db
			.sql("SELECT " + COLUMNS + " FROM pantry_items WHERE user_id = ? AND deleted_at IS NULL"
					+ " ORDER BY expiry_date NULLS LAST, lower(name), id LIMIT " + LIST_LIMIT)
			.param(user)
			.query(this::item)
			.list();
	}

	public Item get(UUID user, UUID id) {
		return find(user, id, false).orElseThrow(ApiException::notFound);
	}

	@Transactional
	public Item create(UUID user, ItemInput in) {
		String name = checked(in);
		Item created = this.db
			.sql("INSERT INTO pantry_items (user_id, name, category, quantity_initial, quantity_remaining, unit,"
					+ " purchase_price, currency_code, purchase_date, expiry_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
					+ " RETURNING " + COLUMNS)
			.params(user, name, in.category(), in.quantity(), in.quantity(), in.unit(), in.purchasePrice(), this.currency,
					in.purchaseDate(), in.expiryDate())
			.query(this::item)
			.single();
		this.alerts.reconcileItem(created.id());
		return created;
	}

	/** Replaces the editable fields. A quantity change is a correction: the used/discarded amount is kept. */
	@Transactional
	public Item update(UUID user, UUID id, ItemInput in, Long expectedVersion) {
		if (expectedVersion == null) {
			throw ApiException.invalidField("version", "Reload the item and try again.");
		}
		String name = checked(in);
		Item current = find(user, id, true).orElseThrow(ApiException::notFound);
		if (current.version() != expectedVersion) {
			throw new ApiException(HttpStatus.CONFLICT, "STALE_UPDATE",
					"This item changed since you loaded it. Reload and try again.");
		}
		BigDecimal consumed = current.quantity().subtract(current.quantityRemaining());
		BigDecimal remaining = in.quantity().subtract(consumed);
		if (remaining.signum() < 0) {
			throw new ApiException(HttpStatus.CONFLICT, "QUANTITY_BELOW_CONSUMED",
					"Quantity can't be less than what was already used or thrown away (" + consumed.stripTrailingZeros()
						.toPlainString() + " " + current.unit() + ").");
		}
		if (!in.unit().equals(current.unit()) && hasEvents(id)) {
			throw new ApiException(HttpStatus.CONFLICT, "UNIT_LOCKED",
					"The unit can't change after use was recorded. Add a new item instead.");
		}
		Item updated = this.db
			.sql("UPDATE pantry_items SET name = ?, category = ?, quantity_initial = ?, quantity_remaining = ?, unit = ?,"
					+ " purchase_price = ?, purchase_date = ?, expiry_date = ?, version = version + 1, updated_at = now()"
					+ " WHERE id = ? AND user_id = ? RETURNING " + COLUMNS)
			.params(name, in.category(), in.quantity(), remaining, in.unit(), in.purchasePrice(), in.purchaseDate(),
					in.expiryDate(), id, user)
			.query(this::item)
			.single();
		this.alerts.reconcileItem(id);
		return updated;
	}

	/** Soft delete: the item leaves the pantry, its history stays, and it never counts as used or saved. */
	@Transactional
	public void delete(UUID user, UUID id) {
		int rows = this.db
			.sql("UPDATE pantry_items SET deleted_at = now(), updated_at = now() WHERE id = ? AND user_id = ?"
					+ " AND deleted_at IS NULL")
			.params(id, user)
			.update();
		if (rows == 0) {
			throw ApiException.notFound();
		}
		this.alerts.reconcileItem(id);
	}

	/**
	 * Records used/finished/discarded and reduces stock in one transaction. The item row is locked, so concurrent
	 * events are applied one at a time. A repeated idempotency key returns the original event without reapplying it.
	 */
	@Transactional
	public EventResult recordEvent(UUID user, UUID itemId, String type, BigDecimal quantity, String notes,
			String idempotencyKey) {
		boolean finished = "finished".equals(type);
		if (finished && quantity != null) {
			throw ApiException.invalidField("quantity", "Leave the amount empty when finishing an item.");
		}
		if (!finished && quantity == null) {
			throw ApiException.invalidField("quantity", "Enter how much.");
		}
		if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
			throw ApiException.invalidField("Idempotency-Key", "Must be 1 to 100 characters.");
		}
		// Lock first, then look for a replay: a retried request waits for the original and then sees its event.
		Item item = find(user, itemId, true).orElseThrow(ApiException::notFound);
		if (idempotencyKey != null) {
			Optional<EventResult> replay = replay(user, itemId, type, quantity, idempotencyKey);
			if (replay.isPresent()) {
				return replay.get();
			}
		}
		BigDecimal amount = finished ? item.quantityRemaining() : quantity;
		if (amount.signum() == 0 || amount.compareTo(item.quantityRemaining()) > 0) {
			throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Only "
					+ item.quantityRemaining().stripTrailingZeros().toPlainString() + " " + item.unit() + " left.");
		}
		Optional<Event> event = this.db
			.sql("INSERT INTO pantry_item_events (user_id, pantry_item_id, event_type, quantity, unit, notes,"
					+ " idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, idempotency_key) DO NOTHING"
					+ " RETURNING id, event_type, quantity, unit, occurred_at, notes")
			.params(user, itemId, type, amount, item.unit(), notes, idempotencyKey)
			.query(this::event)
			.optional();
		if (event.isEmpty()) {
			// The same key was committed concurrently for another item; replay() reports the conflict.
			return replay(user, itemId, type, quantity, idempotencyKey).orElseThrow();
		}
		Item updated = this.db
			.sql("UPDATE pantry_items SET quantity_remaining = quantity_remaining - ?, version = version + 1,"
					+ " updated_at = now() WHERE id = ? AND user_id = ? RETURNING " + COLUMNS)
			.params(amount, itemId, user)
			.query(this::item)
			.single();
		this.alerts.reconcileItem(itemId);
		return new EventResult(event.get(), updated, false);
	}

	private Optional<EventResult> replay(UUID user, UUID itemId, String type, BigDecimal quantity, String key) {
		return this.db
			.sql("SELECT pantry_item_id, id, event_type, quantity, unit, occurred_at, notes FROM pantry_item_events"
					+ " WHERE user_id = ? AND idempotency_key = ?")
			.params(user, key)
			.query((rs, n) -> {
				Event e = event(rs, n);
				boolean same = rs.getObject("pantry_item_id", UUID.class).equals(itemId) && e.type().equals(type)
						&& (quantity == null || quantity.compareTo(e.quantity()) == 0);
				if (!same) {
					throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
							"This request key was already used for a different action.");
				}
				return new EventResult(e, get(user, itemId), true);
			})
			.optional();
	}

	private Optional<Item> find(UUID user, UUID id, boolean lock) {
		return this.db
			.sql("SELECT " + COLUMNS + " FROM pantry_items WHERE id = ? AND user_id = ? AND deleted_at IS NULL"
					+ (lock ? " FOR UPDATE" : ""))
			.params(id, user)
			.query(this::item)
			.optional();
	}

	private boolean hasEvents(UUID itemId) {
		return this.db.sql("SELECT EXISTS (SELECT 1 FROM pantry_item_events WHERE pantry_item_id = ?)")
			.param(itemId)
			.query(Boolean.class)
			.single();
	}

	/** Cross-field rules the DTO annotations cannot express. Returns the trimmed name. */
	private String checked(ItemInput in) {
		if (in.purchaseDate() != null && in.purchaseDate().isAfter(today())) {
			throw ApiException.invalidField("purchaseDate", "Can't be in the future.");
		}
		if (in.expiryDate() != null && in.purchaseDate() != null && in.expiryDate().isBefore(in.purchaseDate())) {
			throw ApiException.invalidField("expiryDate", "Can't be before the purchase date.");
		}
		return in.name().strip();
	}

	private Item item(ResultSet rs, int row) throws SQLException {
		LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
		LocalDate today = today();
		return new Item(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
				rs.getBigDecimal("quantity_initial"), rs.getBigDecimal("quantity_remaining"), rs.getString("unit"),
				rs.getBigDecimal("purchase_price"), rs.getString("currency_code"),
				rs.getObject("purchase_date", LocalDate.class), expiry, Expiry.status(expiry, today),
				Expiry.daysUntil(expiry, today), rs.getLong("version"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

	private Event event(ResultSet rs, int row) throws SQLException {
		return new Event(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getBigDecimal("quantity"),
				rs.getString("unit"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getString("notes"));
	}

}
