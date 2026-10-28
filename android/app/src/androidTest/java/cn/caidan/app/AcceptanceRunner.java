package cn.caidan.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Small no-JUnit acceptance runner. Run only on a real Android runtime/emulator. */
public final class AcceptanceRunner extends Instrumentation {
    private final StringBuilder log = new StringBuilder();
    private int assertions;
    private boolean shoppingRegression;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        Object route = arguments == null ? null : arguments.get("shoppingRegression");
        shoppingRegression = Boolean.TRUE.equals(route) || "true".equalsIgnoreCase(String.valueOf(route));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        Context testContext = null;
        try {
            testContext = new AcceptanceContext(getTargetContext());
            if (shoppingRegression) {
                testShoppingRegression();
            } else {
                testRecipes(testContext);
                testPlanner(testContext);
                testShopping();
                testShoppingRegression();
                testAiSlotValidation(testContext);
                testStore(testContext);
                testPantry(testContext);
                testShoppingLifecycle(testContext);
                testMenuExpiry(testContext);
                testAiHttp(testContext);
            }
            log.append("PASS: ").append(assertions).append(" acceptance assertions");
        } catch (Throwable error) {
            code = Activity.RESULT_FIRST_USER;
            log.append("FAIL after ").append(assertions).append(" assertions: ")
                    .append(error.getClass().getSimpleName()).append(": ").append(error.getMessage());
        } finally {
            if (testContext != null) {
                try { testContext.getSharedPreferences("kitchen", Context.MODE_PRIVATE).edit().clear().commit(); } catch (Throwable ignored) { }
                try { testContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit(); } catch (Throwable ignored) { }
                deleteTree(new File(testContext.getCacheDir(), "acceptance-data"));
            }
        }
        result.putString("stream", log.toString());
        result.putString("result", code == Activity.RESULT_OK ? "PASS" : "FAIL");
        result.putInt("assertions", assertions);
        finish(code, result);
    }

    private void testRecipes(Context context) throws Exception {
        Store store = new Store(context);
        check(store.recipes.size() >= 200, "asset has at least 200 recipes: " + store.recipes.size());
        Set<String> ids = new HashSet<>();
        for (Recipe recipe : store.recipes) {
            new Recipe(recipe.json);
            check(ids.add(recipe.id), "unique recipe id " + recipe.id);
        }
        log.append("Recipes: ").append(store.recipes.size()).append("\n");
    }

    private void testPlanner(Context context) throws Exception {
        List<Recipe> recipes = new Store(context).recipes;
        Planner.Options options = options(1, 3, 3);
        JSONObject one = Planner.generate(recipes, options, new Random(1));
        assertPlan(one, 3, 1, 3, context);
        options = options(7, 4, 3);
        JSONObject seven = Planner.generate(recipes, options, new Random(2));
        assertPlan(seven, 21, 7, 4, context);
        options = options(14, 2, 3);
        JSONObject fourteen = Planner.generate(recipes, options, new Random(3));
        assertPlan(fourteen, 42, 14, 2, context);
        Planner.Options invalid = options(15, 2, 1);
        expectFailure(() -> invalid.validate(), "15-day bound");

        Planner.Options none = options(1, 2, 1);
        none.meals.clear(); none.meals.add("早餐"); none.exclude = "鸡蛋,面包,粥,馒头,包子,饼,饭,豆浆,燕麦,土豆,香蕉,牛奶,玉米,面条,红薯,鸡肉,海鲜";
        expectFailure(() -> Planner.generate(Arrays.asList(recipe("only", "鸡蛋饼", "早餐", 1,
                ingredient("鸡蛋", 1, "个", "", "肉蛋水产"))), none, new Random(1)), "no eligible recipes");

        Planner.Options avoidPeanuts = options(1, 2, 1);
        avoidPeanuts.meals.clear(); avoidPeanuts.meals.add("晚餐"); avoidPeanuts.exclude = "花生";
        Recipe chicken = recipe("rule_chicken", "清炖鸡", "荤菜", 2, ingredient("鸡肉", 300, "g", "", "肉蛋水产"));
        Recipe peanuts = recipe("rule_peanuts", "花生鸡丁", "荤菜", 2, ingredient("花生", 20, "g", "", "调味干货"));
        JSONObject filtered = Planner.generate(Arrays.asList(chicken, peanuts), avoidPeanuts, new Random(4));
        check("rule_chicken".equals(filtered.getJSONArray("slots").getJSONObject(0).getJSONArray("recipeIds").getString(0)),
                "rule planner honors a usable exclusion while retaining valid dishes");
    }

    private void assertPlan(JSONObject plan, int slotsCount, int days, int people, Context context) throws Exception {
        check(plan.getInt("days") == days && plan.getInt("people") == people, "plan retains days and people");
        JSONArray slots = plan.getJSONArray("slots");
        check(slots.length() == slotsCount, "slot count " + slotsCount);
        for (int d = 0; d < days; d++) {
            Set<String> dayMeals = new HashSet<>();
            for (int i = 0; i < slots.length(); i++) {
                JSONObject slot = slots.getJSONObject(i);
                if (slot.getInt("day") != d) continue;
                String meal = slot.getString("meal");
                dayMeals.add(meal);
                JSONArray ids = slot.getJSONArray("recipeIds");
                check(ids.length() == (meal.equals("早餐") ? 1 : 3), "expected dish count for " + meal);
                Set<String> distinct = new HashSet<>();
                for (int j = 0; j < ids.length(); j++) check(distinct.add(ids.getString(j)), "no repeated dish in meal");
            }
            check(dayMeals.equals(new HashSet<>(Arrays.asList("早餐", "午餐", "晚餐"))), "all meals present for day " + d);
        }
        Map<String, Recipe> byId = new Store(context).byId;
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.getJSONObject(i);
            if (slot.getString("meal").equals("早餐")) {
                Recipe recipe = byId.get(slot.getJSONArray("recipeIds").getString(0));
                check(Planner.appropriate(recipe, "早餐"), "breakfast selects breakfast or staple");
            }
        }
    }

    private void testShopping() throws Exception {
        Recipe grams = recipe("g", "番茄炒蛋", "荤菜", 2,
                ingredient("番茄", 100, "g", "", "蔬果"), ingredient("盐", 5, "g", "", "调味干货"),
                ingredient("待定香料", 0, "", "按口味添加", "调味干货"));
        Recipe kilos = recipe("kg", "西红柿汤", "汤羹", 2,
                ingredient("西红柿", .2, "kg", "", "蔬果"), ingredient("盐", 1, "小勺", "", "调味干货"));
        Planner.Options options = options(2, 4, 1); options.meals.clear(); options.meals.add("晚餐");
        JSONObject slot = new JSONObject().put("day", 0).put("meal", "晚餐").put("recipeIds", new JSONArray().put("g"));
        JSONObject slotTomorrow = new JSONObject().put("day", 1).put("meal", "晚餐").put("recipeIds", new JSONArray().put("kg"));
        JSONObject plan = Planner.create(options, new JSONArray().put(slot).put(slotTomorrow), "fixture");
        List<Planner.ShoppingItem> items = Planner.shopping(plan, map(grams, kilos), -1);
        Planner.ShoppingItem tomato = find(items, "番茄");
        check(tomato != null && items.stream().filter(i -> i.name.equals("番茄")).count() == 1
                && Math.abs(tomato.quantities.getOrDefault("克", 0.0) - 600) < .001,
                "aliases, units and unmeasured uses merge to one tomato row and 600g");
        JSONObject sameMeal = new JSONObject().put("day", 0).put("meal", "晚餐")
                .put("recipeIds", new JSONArray().put("g").put("kg"));
        Planner.ShoppingItem sameMealTomato = find(Planner.shopping(
                Planner.create(options, new JSONArray().put(sameMeal), "fixture"), map(grams, kilos), -1), "番茄");
        check(sameMealTomato != null && Math.abs(sameMealTomato.quantities.getOrDefault("克", 0.0) - 600) < .001,
                "same-meal tomato aliases merge to 600g for four people");
        Planner.ShoppingItem todayTomato = find(Planner.shopping(plan, map(grams, kilos), 0), "番茄");
        Planner.ShoppingItem tomorrowTomato = find(Planner.shopping(plan, map(grams, kilos), 1), "番茄");
        check(todayTomato != null && tomorrowTomato != null && Math.abs(todayTomato.quantities.getOrDefault("克", 0.0) - 200) < .001
                && Math.abs(tomorrowTomato.quantities.getOrDefault("克", 0.0) - 400) < .001,
                "day-filtered shopping quantities are 200g and 400g");
        Planner.ShoppingItem salt = find(items, "盐");
        check(salt != null && salt.quantities.containsKey("克") && salt.quantities.containsKey("小勺")
                && items.stream().filter(i -> i.name.equals("盐")).count() == 1, "one salt row preserves multiple units");
        Planner.ShoppingItem unmeasured = find(items, "待定香料");
        check(unmeasured != null && unmeasured.unmeasuredCount == 1 && unmeasured.notes.size() == 1,
                "unmeasured amount remains visible and counted");
    }

    private void testShoppingRegression() throws Exception {
        Recipe tomatoDish = recipe("regression_tomato", "番茄家常菜", "素菜", 2,
                ingredient("西红柿", 100, "g", "", "蔬果"),
                ingredient("番茄", 2, "个", "", "蔬果"),
                ingredient("番茄", 0, "", "适量", "蔬果"));
        Recipe fishDish = recipe("regression_fish", "清蒸鲈鱼", "水产", 2,
                ingredient("鲈鱼", 1, "条", "", "调味干货"));
        Planner.Options options = options(1, 2, 1);
        options.meals.clear(); options.meals.add("晚餐");
        JSONObject slot = new JSONObject().put("day", 0).put("meal", "晚餐")
                .put("recipeIds", new JSONArray().put(tomatoDish.id).put(fishDish.id));
        JSONObject plan = Planner.create(options, new JSONArray().put(slot), "shopping regression");
        List<Planner.ShoppingItem> items = Planner.shopping(plan, map(tomatoDish, fishDish), -1);
        int tomatoRows = 0;
        boolean fishIsProtein = false;
        Planner.ShoppingItem tomato = null;
        for (Planner.ShoppingItem item : items) {
            if (item.name.equals("番茄")) { tomatoRows++; tomato = item; }
            if (item.name.equals("鲈鱼")) fishIsProtein = "肉蛋水产".equals(item.category);
        }
        check(tomatoRows == 1 && tomato != null && tomato.quantities.containsKey("克")
                        && tomato.quantities.containsKey("个") && tomato.unmeasuredCount == 1
                        && tomato.sourceNames.contains("西红柿") && tomato.sourceNames.contains("番茄") && fishIsProtein,
                "shopping regression: tomato rows=" + tomatoRows + ", quantities=" + (tomato == null ? "missing" : tomato.quantities)
                        + ", unmeasured=" + (tomato == null ? -1 : tomato.unmeasuredCount) + "; fish is meat/seafood=" + fishIsProtein);
        check("肉蛋水产".equals(IngredientCatalog.category("鲈鱼"))
                        && "肉蛋水产".equals(IngredientCatalog.category("虾"))
                        && "调味干货".equals(IngredientCatalog.category("鱼露"))
                        && "调味干货".equals(IngredientCatalog.category("蚝油")),
                "fish and shrimp are protein; fish sauce and oyster sauce are seasonings");
    }

    private void testShoppingLifecycle(Context context) throws Exception {
        Set<String> originals = new LinkedHashSet<>(Arrays.asList("青瓜", "盐"));
        String validResponse = rulesResponse("青瓜", "黄瓜", "蔬果", "盐", "盐", "调味干货");
        JSONObject rules = Planner.parseIngredientRules(validResponse, originals);
        check(rules.getJSONObject("青瓜").getString("name").equals("黄瓜") && rules.length() == 2,
                "valid ingredient rules cover each original");
        expectFailure(() -> Planner.parseIngredientRules(rulesResponse("青瓜", "黄瓜", "蔬果"), originals), "incomplete rule set rejected");
        expectFailure(() -> Planner.parseIngredientRules(rulesResponse("青瓜", "黄瓜", "蔬果", "青瓜", "青瓜", "蔬果"), originals), "duplicate rule rejected");
        expectFailure(() -> Planner.parseIngredientRules(rulesResponse("青瓜", "黄瓜", "蔬果", "invented", "盐", "调味干货"), originals), "invented source rejected");
        expectFailure(() -> Planner.parseIngredientRules(rulesResponse("青瓜", "黄瓜", "food", "盐", "盐", "调味干货"), originals), "invalid category rejected");
        Set<String> aliasOriginals = new LinkedHashSet<>(Arrays.asList("西红柿", "番茄"));
        expectFailure(() -> Planner.parseIngredientRules(rulesResponse("西红柿", "番茄", "蔬果", "番茄", "番茄", "调味干货"), aliasOriginals), "conflicting categories for a normalized ingredient rejected");

        Store store = new Store(context);
        Recipe tomatoRecipe = recipe("custom_shop_tomato", "番茄盐菜", "荤菜", 2,
                ingredient("西红柿", 100, "g", "", "其他"), ingredient("盐", 3, "g", "", "其他"));
        Recipe cucumberRecipe = recipe("custom_shop_cucumber", "青瓜菜", "素菜", 2,
                ingredient("青瓜", 1, "个", "", "其他"));
        store.saveRecipe(tomatoRecipe); store.saveRecipe(cucumberRecipe);
        Planner.Options options = options(2, 2, 1); options.meals.clear(); options.meals.add("晚餐");
        JSONObject slotToday = new JSONObject().put("day", 0).put("meal", "晚餐").put("recipeIds", new JSONArray().put(tomatoRecipe.id));
        JSONObject slotTomorrow = new JSONObject().put("day", 1).put("meal", "晚餐").put("recipeIds", new JSONArray().put(cucumberRecipe.id));
        JSONArray slots = new JSONArray().put(slotToday).put(slotTomorrow);
        store.savePlan(Planner.create(options, slots, "lifecycle"));

        List<Planner.ShoppingItem> today = store.shoppingItems(0);
        Planner.ShoppingItem tomatoes = find(today, "番茄");
        Planner.ShoppingItem salt = find(today, "盐");
        check(tomatoes != null && salt != null && store.shoppingItems(1).size() == 1, "shopping filters isolate the selected day");
        store.selectShopping(0, true);
        check(store.checked.contains(tomatoes.key()) && store.checked.contains(salt.key()), "select all marks current day rows");
        store.selectShopping(0, false);
        check(!store.checked.contains(tomatoes.key()) && !store.checked.contains(salt.key()), "deselect all clears current day rows");
        store.check(tomatoes.key(), true);
        check(store.completeShopping(0) == 1, "partial completion records only the selected row");

        store = new Store(context);
        check(find(store.shoppingItems(0), "番茄") == null && find(store.shoppingItems(0), "盐") != null
                && find(store.shoppingItems(1), "青瓜") != null, "partial completion survives Store recreation and preserves unselected/day rows");
        store.selectShopping(1, true);
        check(store.completeShopping(1) == 1, "day-filtered completion marks the selected day");
        store = new Store(context);
        check(store.shoppingItems(1).isEmpty() && find(store.shoppingItems(0), "盐") != null,
                "day completion persists across recreation without completing another day");

        double saltAmount = find(store.shoppingItems(0), "盐").quantities.get("克");
        JSONObject savedRules = Planner.parseIngredientRules(validResponse, originals);
        store.saveIngredientRules(savedRules);
        store = new Store(context);
        check(find(store.shoppingItems(0), "盐").quantities.get("克") == saltAmount,
                "ingredient rules do not change source quantities");
        check(store.shoppingItems(1).isEmpty(), "AI rename of a completed ingredient does not resurrect its source demand");
        String rulesBeforeInvalid = store.ingredientRules.toString();
        JSONObject invalidRules = new JSONObject().put("盐", new JSONObject().put("name", "").put("category", "其他"));
        Store beforeInvalid = store;
        expectFailure(() -> beforeInvalid.saveIngredientRules(invalidRules), "invalid rules are rejected before persistence");
        check(new Store(context).ingredientRules.toString().equals(rulesBeforeInvalid), "invalid AI result leaves saved rules unchanged");

        Recipe changedTomato = recipe(tomatoRecipe.id, "番茄盐菜", "荤菜", 2,
                ingredient("青椒", 100, "g", "", "蔬果"), ingredient("盐", 3, "g", "", "调味干货"));
        store.saveRecipe(changedTomato);
        store = new Store(context);
        check(find(store.shoppingItems(0), "青椒") != null && find(store.shoppingItems(0), "盐") != null
                && store.shoppingItems(1).isEmpty(), "recipe changes reveal new source demand and keep other completed demands done");
        store.selectShopping(0, true);
        check(store.completeShopping(0) == 2 && store.shoppingItems(0).isEmpty(), "select all completes every remaining row");
        store.resetShopping();
        store = new Store(context);
        check(store.checked.isEmpty() && store.shoppingItems(0).size() == 2 && store.shoppingItems(1).size() == 1,
                "reset restores the whole menu and clears selection/completion");
        store.check(find(store.shoppingItems(0), "青椒").key(), true);
        store.completeShopping(0);
        store.savePlan(Planner.create(options, slots, "new menu"));
        store = new Store(context);
        check(store.plan.optJSONArray("completedShopping") == null && store.shoppingItems(0).size() == 2,
                "saving a new plan clears completion and restores full shopping list");
    }

    private void testMenuExpiry(Context context) throws Exception {
        LocalDate monday = LocalDate.of(2026, 10, 26);
        LocalDate wednesday = LocalDate.of(2026, 10, 28);
        check(Planner.firstActiveDay(expiryPlan(monday).put("days", 7), LocalDate.of(2026, 10, 25)) == 0
                        && Planner.firstActiveDay(expiryPlan(monday).put("days", 7), monday) == 0
                        && Planner.firstActiveDay(expiryPlan(monday).put("days", 7), LocalDate.of(2026, 11, 2)) == 7,
                "first active day clamps before start and after end");

        Store store = new Store(context);
        Recipe recipe = recipe("custom_expiry", "过期验收菜", "荤菜", 2,
                ingredient("番茄", 100, "g", "", "蔬果"), ingredient("青瓜", 1, "个", "", "蔬果"));
        store.saveRecipe(recipe); store.favorite(recipe.id); store.savePantry(Collections.singleton("番茄"));
        Planner.Options options = options(7, 2, 1); options.start = monday; options.meals.clear(); options.meals.add("晚餐");
        JSONArray slots = new JSONArray();
        for (int day = 0; day < 7; day++) slots.put(new JSONObject().put("day", day).put("meal", "晚餐")
                .put("recipeIds", new JSONArray().put(recipe.id)));
        store.savePlan(Planner.create(options, slots, "expiry"));
        String expiredKey = find(store.shoppingItems(0), "番茄").key();
        String todayKey = find(store.shoppingItems(2), "番茄").key();
        String futureKey = find(store.shoppingItems(6), "黄瓜").key();
        store.check(expiredKey, true); store.check(todayKey, true); store.check(futureKey, true);
        store.selectShopping(4, true);
        check(store.completeShopping(4) == 2, "future-day items can be completed before pruning");
        Set<String> completedBefore = new HashSet<>(stringList(store.plan.getJSONArray("completedShopping")));
        JSONObject rulesBefore = new JSONObject(store.ingredientRules.toString());
        Set<String> customBefore = new HashSet<>();
        for (Recipe item : store.recipes) if (item.custom) customBefore.add(item.id);
        check(new Store(context).plan.getJSONArray("slots").length() == 7, "Store recreation does not prune expired slots automatically");

        check(store.prunePastMenus(wednesday), "prune reports removal of past menu slots");
        store = new Store(context);
        JSONArray kept = store.plan.getJSONArray("slots");
        boolean keptDays = kept.length() == 5;
        for (int i = 0; i < kept.length(); i++) keptDays &= kept.getJSONObject(i).getInt("day") == i + 2;
        check(keptDays && store.plan.getString("start").equals(monday.toString()) && store.plan.getInt("days") == 7,
                "partial pruning removes only days 0-1 and preserves original plan dates");
        check(find(store.shoppingItems(0), "番茄") == null && store.shoppingItems(1).isEmpty()
                        && find(store.shoppingItems(2), "番茄") != null && store.shoppingItems(4).isEmpty()
                        && find(store.shoppingItems(6), "黄瓜") != null,
                "expired needs disappear while today/future needs and completion remain intact");
        check(!store.checked.contains(expiredKey) && store.checked.contains(todayKey) && store.checked.contains(futureKey),
                "pruning drops expired selections and keeps visible checked keys");
        check(completedBefore.equals(new HashSet<>(stringList(store.plan.getJSONArray("completedShopping")))),
                "pruning preserves completed future source keys");
        check(!store.prunePastMenus(wednesday), "repeated pruning with no expired slots reports no change");
        Store repeat = new Store(context);
        check(repeat.plan.toString().equals(store.plan.toString()), "no-op pruning leaves persisted plan unchanged");
        Set<String> customAfter = new HashSet<>();
        for (Recipe item : repeat.recipes) if (item.custom) customAfter.add(item.id);
        check(repeat.pantry.contains("番茄") && repeat.favorites.contains(recipe.id)
                        && repeat.ingredientRules.toString().equals(rulesBefore.toString()) && customAfter.equals(customBefore),
                "pruning preserves pantry, favorites, rules and custom recipes");

        check(repeat.prunePastMenus(LocalDate.of(2026, 11, 2)), "pruning beyond menu end expires remaining plan");
        check(new Store(context).plan == null, "fully expired plan remains cleared after Store recreation");
        Planner.Options futureOptions = options(2, 2, 1); futureOptions.start = LocalDate.of(2026, 11, 3);
        futureOptions.meals.clear(); futureOptions.meals.add("晚餐");
        JSONObject futurePlan = Planner.create(futureOptions, new JSONArray().put(
                new JSONObject().put("day", 0).put("meal", "晚餐").put("recipeIds", new JSONArray().put(recipe.id))), "future");
        Store future = new Store(context); future.savePlan(futurePlan);
        String futureBefore = future.plan.toString();
        check(!future.prunePastMenus(LocalDate.of(2026, 11, 2)) && new Store(context).plan.toString().equals(futureBefore),
                "future plan is unchanged when pruning before its start");
    }

    private static JSONObject expiryPlan(LocalDate start) throws Exception {
        return new JSONObject().put("start", start.toString()).put("days", 1);
    }

    private void testAiSlotValidation(Context context) throws Exception {
        List<Recipe> all = new Store(context).recipes;
        Recipe meat = recipe("allowed", "清炖鸡", "荤菜", 2, ingredient("鸡肉", 1, "斤", "", "肉蛋水产"));
        Recipe veg = recipe("vegetable", "炒青菜", "素菜", 2, ingredient("青菜", 200, "g", "", "蔬果"));
        Planner.Options o = options(1, 2, 2);
        o.meals.clear(); o.meals.add("晚餐");
        String valid = "```json\n{\"slots\":[{\"day\":0,\"meal\":\"晚餐\",\"recipeIds\":[\"allowed\",\"vegetable\"]}]}\n```";
        check(Planner.validateAiSlots(valid, Arrays.asList(meat, veg), o).length() == 1, "valid fenced AI JSON accepted");
        expectFailure(() -> Planner.validateAiSlots(response("missing", "vegetable"), Arrays.asList(meat, veg), o), "unknown AI id rejected");
        expectFailure(() -> Planner.validateAiSlots(response("allowed", "allowed"), Arrays.asList(meat, veg), o), "repeated AI dish rejected");
        o.meals.add("午餐");
        expectFailure(() -> Planner.validateAiSlots(valid, Arrays.asList(meat, veg), o), "missing AI meal rejected");
        Planner.Options repeated = options(1, 2, 1); repeated.meals.clear(); repeated.meals.addAll(Arrays.asList("晚餐", "午餐"));
        JSONObject repeatedSlots = new JSONObject().put("slots", new JSONArray()
                .put(new JSONObject().put("day", 0).put("meal", "晚餐").put("recipeIds", new JSONArray().put("allowed")))
                .put(new JSONObject().put("day", 0).put("meal", "晚餐").put("recipeIds", new JSONArray().put("allowed"))));
        expectFailure(() -> Planner.validateAiSlots(repeatedSlots.toString(), Arrays.asList(meat, veg), repeated), "duplicate AI slot rejected");
        Recipe forbidden = recipe("forbidden", "花生鸡丁", "荤菜", 2, ingredient("花生", 10, "g", "", "调味干货"));
        Planner.Options dislikes = options(1, 2, 1); dislikes.meals.clear(); dislikes.meals.add("晚餐"); dislikes.exclude = "花生";
        expectFailure(() -> Planner.validateAiSlots(response("forbidden"), Arrays.asList(forbidden), dislikes), "disliked AI recipe rejected");
        check(!all.isEmpty(), "asset available for AI checks");
    }

    private void testStore(Context context) throws Exception {
        Store store = new Store(context);
        Recipe custom = recipe("custom_acceptance", "自建测试菜", "早餐", 2,
                ingredient("鸡蛋", 2, "个", "", "肉蛋水产"));
        store.saveRecipe(custom);
        Recipe edited = recipe(custom.id, "编辑后的测试菜", "早餐", 2, ingredient("鸡蛋", 3, "个", "", "肉蛋水产"));
        store.saveRecipe(edited);
        check(store.byId.get(custom.id).name.equals("编辑后的测试菜"), "custom recipe create and edit");
        JSONObject slot = new JSONObject().put("day", 0).put("meal", "早餐").put("recipeIds", new JSONArray().put(custom.id));
        store.savePlan(new JSONObject().put("start", LocalDate.now().toString()).put("days", 1).put("people", 2)
                .put("slots", new JSONArray().put(slot)));
        expectFailure(() -> store.deleteRecipe(store.byId.get(custom.id)), "custom recipe referenced by plan protected");
        String favoriteId = "acceptance_favorite";
        store.favorite(favoriteId); store.check("acceptance_check", true);
        Store restored = new Store(context);
        check(restored.plan != null && restored.plan.getJSONArray("slots").length() == 1, "plan persists");
        check(restored.favorites.contains(favoriteId) && restored.checked.contains("acceptance_check"), "favorite and shopping check persist");
        restored.savePlan(new JSONObject().put("slots", new JSONArray()));
        restored.deleteRecipe(restored.byId.get(custom.id));
        check(!new Store(context).byId.containsKey(custom.id), "unreferenced custom recipe deleted");
    }

    private void testPantry(Context context) throws Exception {
        Set<String> parsed = Planner.parsePantry(" 西红柿,番茄、马铃薯；土豆\n生姜,大蒜，蒜瓣,,  ,小米,大米");
        check(new ArrayList<>(parsed).equals(Arrays.asList("番茄", "土豆", "姜", "蒜", "小米", "大米")),
                "pantry parsing trims separators, removes blanks and deduplicates aliases");

        Recipe duplicateNames = recipe("pantry_duplicates", "番茄蒜菜", "荤菜", 2,
                ingredient("西红柿", 1, "个", "", "蔬果"), ingredient("番茄", 2, "个", "", "蔬果"),
                ingredient("大蒜", 1, "瓣", "", "蔬果"), ingredient("大米", 100, "g", "", "米面粮油"));
        check(Planner.pantryMatches(duplicateNames, parsed).equals(Arrays.asList("番茄", "蒜", "大米")),
                "ingredient alias matches are normalized and returned once in ingredient order");
        Recipe rice = recipe("pantry_rice", "大米饭", "主食", 2,
                ingredient("大米", 200, "g", "", "米面粮油"));
        check(Planner.pantryMatches(rice, Collections.singleton("小米")).isEmpty(),
                "pantry matching does not confuse similar but distinct names");

        Recipe oneMatch = recipe("pantry_one", "单食材", "荤菜", 2, ingredient("西红柿", 1, "个", "", "蔬果"));
        Recipe tiedMatch = recipe("pantry_tied", "另一个食材", "荤菜", 2, ingredient("大蒜", 1, "瓣", "", "蔬果"));
        Recipe noMatch = recipe("pantry_none", "没有食材", "荤菜", 2, ingredient("鸡肉", 100, "g", "", "肉蛋水产"));
        List<Recipe> original = Arrays.asList(oneMatch, tiedMatch, noMatch, duplicateNames);
        List<Recipe> prioritized = Planner.prioritizePantry(original, parsed);
        check(prioritized.equals(Arrays.asList(duplicateNames, oneMatch, tiedMatch, noMatch)),
                "pantry priority sorts by match count and keeps ties stable");
        check(original.get(0) == oneMatch && original.get(1) == tiedMatch,
                "pantry prioritization leaves the input list unchanged");
        check(Planner.prioritizePantry(original, Collections.emptySet()).equals(original),
                "empty pantry preserves original order");

        Recipe stockedMeat = recipe("pantry_meat", "有现成番茄的荤菜", "荤菜", 2,
                ingredient("番茄", 1, "个", "", "蔬果"));
        Recipe plainMeat = recipe("pantry_plain_meat", "普通荤菜", "荤菜", 2,
                ingredient("鸡肉", 200, "g", "", "肉蛋水产"));
        for (int seed = 0; seed < 20; seed++) {
            Planner.Options ranked = options(1, 2, 1);
            ranked.meals.clear(); ranked.meals.add("晚餐"); ranked.pantry.add("番茄");
            JSONObject selected = Planner.generate(Arrays.asList(plainMeat, stockedMeat), ranked, new Random(seed));
            check("pantry_meat".equals(selected.getJSONArray("slots").getJSONObject(0)
                    .getJSONArray("recipeIds").getString(0)), "matched same-category recipe wins seed " + seed);
        }

        Recipe forbiddenMatch = recipe("pantry_forbidden", "花生鸡丁", "荤菜", 2,
                ingredient("花生", 20, "g", "", "调味干货"));
        Planner.Options exclude = options(1, 2, 1);
        exclude.meals.clear(); exclude.meals.add("晚餐"); exclude.exclude = "花生"; exclude.pantry.add("花生");
        JSONObject safe = Planner.generate(Arrays.asList(plainMeat, forbiddenMatch), exclude, new Random(6));
        check("pantry_plain_meat".equals(safe.getJSONArray("slots").getJSONObject(0)
                .getJSONArray("recipeIds").getString(0)), "pantry preference never overrides exclusion");

        Recipe matchedVegetable = recipe("pantry_vegetable", "番茄青菜", "素菜", 2,
                ingredient("番茄", 2, "个", "", "蔬果"));
        Planner.Options category = options(1, 2, 1);
        category.meals.clear(); category.meals.add("晚餐"); category.pantry.add("番茄");
        JSONObject dinner = Planner.generate(Arrays.asList(matchedVegetable, plainMeat), category, new Random(8));
        check("pantry_plain_meat".equals(dinner.getJSONArray("slots").getJSONObject(0)
                .getJSONArray("recipeIds").getString(0)), "dish category preference remains ahead of pantry matching");

        Recipe secondMeat = recipe("pantry_second", "第二道荤菜", "荤菜", 2,
                ingredient("牛肉", 200, "g", "", "肉蛋水产"));
        Planner.Options twoDays = options(2, 2, 1);
        twoDays.meals.clear(); twoDays.meals.add("晚餐"); twoDays.pantry.add("番茄");
        JSONObject repeatCheck = Planner.generate(Arrays.asList(stockedMeat, plainMeat, secondMeat), twoDays, new Random(9));
        String first = repeatCheck.getJSONArray("slots").getJSONObject(0).getJSONArray("recipeIds").getString(0);
        String second = repeatCheck.getJSONArray("slots").getJSONObject(1).getJSONArray("recipeIds").getString(0);
        check(!first.equals(second), "two-day plan avoids repeats when eligible recipes are sufficient");

        Planner.Options defaultPantry = options(2, 2, 1); defaultPantry.meals.clear(); defaultPantry.meals.add("晚餐");
        Planner.Options emptyPantry = options(2, 2, 1); emptyPantry.meals.clear(); emptyPantry.meals.add("晚餐");
        JSONObject defaultResult = Planner.generate(Arrays.asList(stockedMeat, plainMeat, secondMeat), defaultPantry, new Random(10));
        JSONObject emptyResult = Planner.generate(Arrays.asList(stockedMeat, plainMeat, secondMeat), emptyPantry, new Random(10));
        check(defaultResult.toString().equals(emptyResult.toString()), "empty pantry behaves exactly like default planning");

        Planner.Options snapshotOptions = options(1, 2, 1);
        snapshotOptions.meals.clear(); snapshotOptions.meals.add("晚餐"); snapshotOptions.pantry.addAll(parsed);
        JSONObject snapshotPlan = Planner.generate(Arrays.asList(stockedMeat, plainMeat), snapshotOptions, new Random(11));
        snapshotOptions.pantry.clear();
        check(new HashSet<>(Arrays.asList("番茄", "土豆", "姜", "蒜", "小米", "大米"))
                .equals(new HashSet<>(stringList(snapshotPlan.getJSONArray("pantry")))),
                "generated plan captures an independent pantry snapshot");

        Store before = new Store(context);
        String planBefore = before.plan == null ? null : before.plan.toString();
        Set<String> checkBefore = new HashSet<>(before.checked), favoritesBefore = new HashSet<>(before.favorites);
        Set<String> customBefore = new HashSet<>();
        for (Recipe value : before.recipes) if (value.custom) customBefore.add(value.id);
        before.savePantry(parsed);
        Store saved = new Store(context);
        check(saved.pantry.equals(parsed), "saved pantry survives Store recreation");
        Set<String> reduced = new LinkedHashSet<>(saved.pantry); reduced.remove("番茄");
        saved.savePantry(reduced);
        Store removed = new Store(context);
        check(removed.pantry.equals(reduced) && !removed.pantry.contains("番茄"), "removing one pantry item persists");
        removed.savePantry(Collections.emptySet());
        Store cleared = new Store(context);
        check(cleared.pantry.isEmpty(), "clearing pantry persists across Store recreation");
        Set<String> customAfter = new HashSet<>();
        for (Recipe value : cleared.recipes) if (value.custom) customAfter.add(value.id);
        check((planBefore == null ? cleared.plan == null : cleared.plan != null && planBefore.equals(cleared.plan.toString()))
                && cleared.checked.equals(checkBefore) && cleared.favorites.equals(favoritesBefore)
                && customAfter.equals(customBefore), "pantry edits preserve plan, custom recipes, checks and favorites");
    }

    private void testAiHttp(Context context) throws Exception {
        String token = "acceptance-secret-" + System.nanoTime();
        AiSettings.Config saved = new AiSettings.Config();
        saved.baseUrl = "http://127.0.0.1/v1"; saved.model = "vision-test"; saved.apiKey = token;
        AiSettings.save(context, saved);
        AiSettings.Config loaded = AiSettings.load(context);
        check(token.equals(loaded.apiKey) && saved.baseUrl.equals(loaded.baseUrl), "encrypted AI settings round trip");
        File prefFile = new File(getTargetContext().getApplicationInfo().dataDir, "shared_prefs/acceptance_ai_settings.xml");
        check(prefFile.isFile() && !new String(readAll(prefFile), StandardCharsets.UTF_8).contains(token), "plaintext key absent from preferences file");

        AtomicReference<String> request = new AtomicReference<>("");
        FakeServer models = new FakeServer(200, "{\"data\":[{\"id\":\"vision-test\"}]}", null);
        AiSettings.Config config = config(models.port, token);
        check(AiClient.models(config).equals(Arrays.asList("vision-test")), "models endpoint decoded");
        check(models.failure.get() == null && models.request.get().contains("Authorization: Bearer " + token), "models request authorization");

        FakeServer complete = new FakeServer(200, "{\"choices\":[{\"message\":{\"content\":\"accepted\"}}]}", null);
        config = config(complete.port, token);
        String imageDataUrl = "data:image/png;base64,aGVsbG8=";
        check("accepted".equals(AiClient.complete(config, "system", "extract this recipe", imageDataUrl)), "chat response decoded");
        String wire = complete.request.get();
        int bodyStart = wire.indexOf("\r\n\r\n");
        check(bodyStart >= 0, "chat request has HTTP body separator");
        JSONObject sentBody = new JSONObject(wire.substring(bodyStart + 4));
        JSONArray sentMessages = sentBody.getJSONArray("messages");
        JSONArray sentContent = sentMessages.getJSONObject(1).getJSONArray("content");
        boolean sentText = false, sentImage = false;
        for (int i = 0; i < sentContent.length(); i++) {
            JSONObject part = sentContent.getJSONObject(i);
            if ("text".equals(part.optString("type"))) sentText = "extract this recipe".equals(part.optString("text"));
            if ("image_url".equals(part.optString("type"))) sentImage = imageDataUrl.equals(part.getJSONObject("image_url").optString("url"));
        }
        check(sentText && sentImage, "vision request preserves user text and decoded image URL");

        FakeServer unauthorized = new FakeServer(401, "unauthorized", null);
        try { AiClient.models(config(unauthorized.port, token)); fail("401 rejected"); }
        catch (Exception error) { check(error.getMessage().contains("密钥") && !error.getMessage().contains(token), "401 gives safe credential error"); }
        FakeServer redirect = new FakeServer(302, "", "http://127.0.0.1:1/collect");
        try { AiClient.models(config(redirect.port, token)); fail("302 rejected"); }
        catch (Exception error) { check(error.getMessage().contains("重定向"), "302 rejected without following"); }
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private void fail(String message) { throw new AssertionError(message); }
    private void expectFailure(Throwing action, String message) throws Exception {
        try { action.run(); fail(message); } catch (IllegalArgumentException | org.json.JSONException expected) { assertions++; }
    }
    private static Planner.Options options(int days, int people, int dishes) {
        Planner.Options o = new Planner.Options(); o.start = LocalDate.of(2026, 10, 27); o.days = days; o.people = people; o.dishes = dishes;
        o.meals.clear(); o.meals.addAll(Arrays.asList("早餐", "午餐", "晚餐")); return o;
    }
    private static Recipe recipe(String id, String name, String category, int servings, JSONObject... ingredients) throws Exception {
        JSONArray items = new JSONArray(); for (JSONObject item : ingredients) items.put(item);
        return new Recipe(new JSONObject().put("id", id).put("name", name).put("category", category).put("servings", servings)
                .put("servingsEstimated", false).put("custom", id.startsWith("custom_"))
                .put("ingredients", items).put("steps", new JSONArray().put("加热并烹熟")));
    }
    private static JSONObject ingredient(String name, double amount, String unit, String note, String category) throws Exception {
        return new JSONObject().put("name", name).put("amount", amount).put("unit", unit).put("note", note).put("category", category);
    }
    private static Map<String, Recipe> map(Recipe... recipes) { java.util.LinkedHashMap<String, Recipe> m = new java.util.LinkedHashMap<>(); for (Recipe r : recipes) m.put(r.id, r); return m; }
    private static Planner.ShoppingItem find(List<Planner.ShoppingItem> items, String name) { for (Planner.ShoppingItem i : items) if (i.name.equals(name)) return i; return null; }
    private static String rulesResponse(String... triples) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            items.put(new JSONObject().put("original", triples[i]).put("name", triples[i + 1]).put("category", triples[i + 2]));
        }
        return new JSONObject().put("items", items).toString();
    }
    private static String response(String... ids) throws Exception {
        JSONArray values = new JSONArray(); for (String id : ids) values.put(id);
        return new JSONObject().put("slots", new JSONArray().put(new JSONObject().put("day", 0).put("meal", "晚餐").put("recipeIds", values))).toString();
    }
    private static List<String> stringList(JSONArray values) throws Exception {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return result;
    }
    private static AiSettings.Config config(int port, String token) {
        AiSettings.Config c = new AiSettings.Config(); c.baseUrl = "http://127.0.0.1:" + port + "/v1";
        c.apiKey = token; c.model = "vision-test"; return c;
    }
    private interface Throwing { void run() throws Exception; }
    private static byte[] readAll(File file) throws Exception { try (InputStream in = new java.io.FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray(); } }
    private static void deleteTree(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); file.delete(); }

    private static final class AcceptanceContext extends ContextWrapper {
        private final File files;
        AcceptanceContext(Context target) { super(target); files = new File(target.getCacheDir(), "acceptance-data"); deleteTree(files); files.mkdirs(); }
        @Override public File getFilesDir() { return files; }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("acceptance_" + name, mode); }
    }

    private static final class FakeServer {
        final int port;
        final AtomicReference<String> request = new AtomicReference<>("");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        FakeServer(int status, String body, String redirect) throws Exception {
            ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(15000); port = server.getLocalPort();
            Thread worker = new Thread(() -> {
                try (ServerSocket listener = server; Socket client = listener.accept()) {
                    client.setSoTimeout(10000);
                    ByteArrayOutputStream header = new ByteArrayOutputStream(); InputStream input = client.getInputStream();
                    int matched = 0, d;
                    while ((d = input.read()) != -1) {
                        header.write(d);
                        if (matched == 0) matched = d == '\r' ? 1 : 0;
                        else if (matched == 1) matched = d == '\n' ? 2 : d == '\r' ? 1 : 0;
                        else if (matched == 2) matched = d == '\r' ? 3 : 0;
                        else if (matched == 3) matched = d == '\n' ? 4 : d == '\r' ? 1 : 0;
                        if (matched == 4) break;
                    }
                    String headers = new String(header.toByteArray(), StandardCharsets.ISO_8859_1);
                    int length = 0; for (String line : headers.split("\r\n")) if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    byte[] requestBody = new byte[length]; int offset = 0; while (offset < length) { int count = input.read(requestBody, offset, length - offset); if (count < 0) break; offset += count; }
                    request.set(headers + new String(requestBody, 0, offset, StandardCharsets.UTF_8));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    OutputStream output = client.getOutputStream();
                    output.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : status == 302 ? " Found" : " Unauthorized") + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n" + (redirect == null ? "" : "Location: " + redirect + "\r\n") + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.write(bytes); output.flush();
                } catch (Throwable error) { failure.set(error); }
            });
            worker.start();
        }
    }
}
