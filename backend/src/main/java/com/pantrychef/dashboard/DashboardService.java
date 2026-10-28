package com.pantrychef.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.pantrychef.alerts.ExpiryAlerts;
import com.pantrychef.web.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Dashboard figures (FR-019–FR-022, A-06). Everything is derived from recorded events and stored recipe data:
 * <ul>
 * <li><b>Food saved</b>: {@code used}/{@code finished} events on or before the item's expiry date (APP_TIMEZONE).
 * Items without an expiry date, use after expiry, thrown-away stock and deleted stock never count as saved.</li>
 * <li><b>Money saved</b>: each saved event's quantity × (purchase price ÷ purchased quantity), per currency; events
 * on items without a price are counted as "unknown price", never as zero.</li>
 * <li><b>Nutrition</b>: one serving of each planned recipe, from the recipe's per-serving values; meals without
 * values are counted and reported, never treated as zero.</li>
 * </ul>
 */
@Service
public class DashboardService {

	static final String NOTE = "Food and money saved are estimates calculated from the use you recorded before each "
			+ "item's expiry date. Nutrition is per person (one serving of each planned meal) and only includes "
			+ "meals with known nutrition data. This is not medical or dietary advice.";

	private static final Map<String, Object[]> BASE_UNITS = Map.of("g", new Object[] { "g", 1 }, "kg",
			new Object[] { "g", 1000 }, "ml", new Object[] { "ml", 1 }, "l", new Object[] { "ml", 1000 }, "piece",
			new Object[] { "piece", 1 }, "dozen", new Object[] { "piece", 12 }, "bunch", new Object[] { "bunch", 1 },
			"pack", new Object[] { "pack", 1 });

	private final JdbcClient db;

	private final ExpiryAlerts dates;

	private final ZoneId zone;

	public DashboardService(JdbcClient db, ExpiryAlerts dates, @Value("${pantrychef.timezone}") ZoneId zone) {
		this.db = db;
		this.dates = dates;
		this.zone = zone;
	}

	public record Window(LocalDate from, LocalDate to, String timezone) {
	}

	public record Amount(String unit, BigDecimal amount) {
	}

	public record Money(String currency, BigDecimal amount) {
	}

	public record FoodSaved(int items, int events, List<Amount> quantities, int usedAfterExpiry,
			int usedWithoutExpiryDate) {
	}

	public record ThrownAway(int items, int events, List<Amount> quantities) {
	}

	public record MoneySaved(List<Money> totals, int eventsWithUnknownPrice) {
	}

	public record Day(LocalDate date, int savedEvents, int discardedEvents, BigDecimal moneySaved) {
	}

	public record Nutrients(BigDecimal calories, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG) {
	}

	public record NutritionDay(LocalDate date, int plannedMeals, int mealsWithNutrition, Nutrients total) {
	}

	public record Nutrition(UUID planId, LocalDate startDate, int plannedMeals, int mealsWithNutrition,
			boolean complete, Nutrients total, List<NutritionDay> days) {
	}

	public record Summary(Window window, String currency, FoodSaved foodSaved, ThrownAway thrownAway,
			MoneySaved moneySaved, List<Day> daily, Nutrition nutrition, String note) {
	}

	private record Event(String type, LocalDate date, LocalDate expiry, UUID itemId, BigDecimal quantity, String unit,
			BigDecimal price, BigDecimal purchased, String currency) {
	}

	public Summary summary(UUID user, int days, String currency, UUID planId) {
		LocalDate to = this.dates.today();
		LocalDate from = to.minusDays(days - 1L);
		List<Event> events = this.db
			.sql("SELECT e.event_type, (e.occurred_at AT TIME ZONE ?)::date AS day, i.expiry_date, i.id, e.quantity,"
					+ " e.unit, i.purchase_price, i.quantity_initial, i.currency_code FROM pantry_item_events e"
					+ " JOIN pantry_items i ON i.id = e.pantry_item_id WHERE e.user_id = ? AND i.user_id = ?"
					+ " AND (e.occurred_at AT TIME ZONE ?)::date BETWEEN ? AND ?")
			.params(this.zone.getId(), user, user, this.zone.getId(), from, to)
			.query((rs, n) -> new Event(rs.getString(1), rs.getObject(2, LocalDate.class),
					rs.getObject(3, LocalDate.class), rs.getObject(4, UUID.class), rs.getBigDecimal(5), rs.getString(6),
					rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9)))
			.list();

		Map<LocalDate, int[]> counts = new TreeMap<>();
		Map<LocalDate, BigDecimal> moneyByDay = new TreeMap<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			counts.put(d, new int[2]);
			moneyByDay.put(d, BigDecimal.ZERO);
		}
		Map<String, BigDecimal> saved = new TreeMap<>();
		Map<String, BigDecimal> thrown = new TreeMap<>();
		Map<String, BigDecimal> money = new TreeMap<>();
		Set<UUID> savedItems = new HashSet<>();
		Set<UUID> thrownItems = new HashSet<>();
		int savedEvents = 0;
		int thrownEvents = 0;
		int afterExpiry = 0;
		int noExpiry = 0;
		int unknownPrice = 0;
		for (Event e : events) {
			if ("discarded".equals(e.type())) {
				thrownEvents++;
				thrownItems.add(e.itemId());
				add(thrown, e.quantity(), e.unit());
				counts.get(e.date())[1]++;
				continue;
			}
			if (e.expiry() == null) {
				noExpiry++;
				continue;
			}
			if (e.date().isAfter(e.expiry())) {
				afterExpiry++;
				continue;
			}
			savedEvents++;
			savedItems.add(e.itemId());
			add(saved, e.quantity(), e.unit());
			counts.get(e.date())[0]++;
			if (e.price() == null) {
				unknownPrice++;
				continue;
			}
			BigDecimal value = e.quantity().multiply(e.price()).divide(e.purchased(), 2, RoundingMode.HALF_UP);
			money.merge(e.currency(), value, BigDecimal::add);
			if (e.currency().equals(currency)) {
				moneyByDay.merge(e.date(), value, BigDecimal::add);
			}
		}
		List<Day> daily = new ArrayList<>();
		counts.forEach((d, c) -> daily.add(new Day(d, c[0], c[1], moneyByDay.get(d))));
		List<Money> totals = new ArrayList<>();
		money.forEach((c, a) -> totals.add(new Money(c, a)));
		return new Summary(new Window(from, to, this.zone.getId()), currency,
				new FoodSaved(savedItems.size(), savedEvents, amounts(saved), afterExpiry, noExpiry),
				new ThrownAway(thrownItems.size(), thrownEvents, amounts(thrown)), new MoneySaved(totals, unknownPrice),
				daily, nutrition(user, planId), NOTE);
	}

	private Nutrition nutrition(UUID user, UUID planId) {
		Optional<Object[]> plan = this.db
			.sql("SELECT id, start_date FROM meal_plans WHERE user_id = ? AND (?::uuid IS NULL OR id = ?)"
					+ " ORDER BY created_at DESC, id LIMIT 1")
			.params(user, planId, planId)
			.query((rs, n) -> new Object[] { rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class) })
			.optional();
		if (plan.isEmpty()) {
			if (planId != null) {
				throw ApiException.notFound();
			}
			return null;
		}
		UUID id = (UUID) plan.get()[0];
		Map<LocalDate, List<BigDecimal[]>> meals = new TreeMap<>();
		this.db
			.sql("SELECT e.plan_date, r.calories_per_serving, r.protein_g_per_serving, r.carbs_g_per_serving,"
					+ " r.fat_g_per_serving FROM meal_plan_entries e JOIN recipes r ON r.id = e.recipe_id"
					+ " WHERE e.meal_plan_id = ? ORDER BY e.plan_date")
			.param(id)
			.query((rs, n) -> meals.computeIfAbsent(rs.getObject(1, LocalDate.class), (k) -> new ArrayList<>())
				.add(new BigDecimal[] { rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
						rs.getBigDecimal(5) }))
			.list();
		List<NutritionDay> days = new ArrayList<>();
		List<BigDecimal[]> all = new ArrayList<>();
		meals.forEach((day, list) -> {
			all.addAll(list);
			days.add(new NutritionDay(day, list.size(), known(list).size(), total(list)));
		});
		int withData = known(all).size();
		return new Nutrition(id, (LocalDate) plan.get()[1], all.size(), withData,
				!all.isEmpty() && withData == all.size(), total(all), days);
	}

	/** Meals whose four values are all known; a meal with any value missing is "without nutrition". */
	private static List<BigDecimal[]> known(List<BigDecimal[]> meals) {
		return meals.stream().filter((m) -> m[0] != null && m[1] != null && m[2] != null && m[3] != null).toList();
	}

	/** Sum over meals with data; null (not zero) when no meal has data. */
	private static Nutrients total(List<BigDecimal[]> meals) {
		List<BigDecimal[]> known = known(meals);
		if (known.isEmpty()) {
			return null;
		}
		BigDecimal[] sum = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
		for (BigDecimal[] m : known) {
			for (int i = 0; i < 4; i++) {
				sum[i] = sum[i].add(m[i]);
			}
		}
		return new Nutrients(sum[0], sum[1], sum[2], sum[3]);
	}

	private static void add(Map<String, BigDecimal> totals, BigDecimal quantity, String unit) {
		Object[] base = BASE_UNITS.get(unit);
		totals.merge((String) base[0], quantity.multiply(BigDecimal.valueOf((Integer) base[1])), BigDecimal::add);
	}

	private static List<Amount> amounts(Map<String, BigDecimal> totals) {
		List<Amount> list = new ArrayList<>();
		totals.forEach((u, a) -> list.add(new Amount(u, a.stripTrailingZeros())));
		return list;
	}

}
