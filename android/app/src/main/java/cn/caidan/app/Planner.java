package cn.caidan.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class Planner {
    public static final List<String> SHOPPING_CATEGORIES = Arrays.asList("蔬果", "肉蛋水产", "米面粮油", "调味干货", "其他");
    public static final class Options {
        public LocalDate start = LocalDate.now();
        public int days = 7, people = 2, dishes = 2;
        public final List<String> meals = new ArrayList<>(Arrays.asList("午餐", "晚餐"));
        public final Set<String> pantry = new java.util.LinkedHashSet<>();
        public String exclude = "", request = "";
        public void validate() {
            if (days < 1 || days > 14 || people < 1 || people > 12 || dishes < 1 || dishes > 3 || meals.isEmpty()) {
                throw new IllegalArgumentException("请选择 1–14 天、1–12 人及至少一个餐次");
            }
            if (new HashSet<>(meals).size() != meals.size() || !Arrays.asList("早餐", "午餐", "晚餐").containsAll(meals)) {
                throw new IllegalArgumentException("餐次无效");
            }
        }
        public String[] excludedWords() { return exclude.trim().split("[，,、;；\\s]+"); }
    }

    public static List<Recipe> eligible(List<Recipe> recipes, Options options) {
        List<Recipe> result = new ArrayList<>();
        for (Recipe recipe : recipes) if (!recipe.excludes(options.excludedWords()) && !recipe.category.equals("其他")) result.add(recipe);
        return result;
    }

    public static int firstActiveDay(JSONObject plan, LocalDate today) throws JSONException {
        long elapsed = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(plan.getString("start")), today);
        return (int) Math.min(plan.getInt("days"), Math.max(0L, elapsed));
    }

    public static Set<String> parsePantry(String text) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (String part : text.split("[，,、;；\\r\\n]+")) {
            String name = normalizeName(part);
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    public static List<String> pantryMatches(Recipe recipe, Set<String> pantry) {
        if (pantry.isEmpty()) return new ArrayList<>();
        Set<String> available = new HashSet<>();
        for (String name : pantry) available.add(normalizeName(name));
        Set<String> matches = new java.util.LinkedHashSet<>();
        for (Recipe.Ingredient ingredient : recipe.ingredients) {
            String name = normalizeName(ingredient.name);
            if (available.contains(name)) matches.add(name);
        }
        return new ArrayList<>(matches);
    }

    public static List<Recipe> prioritizePantry(List<Recipe> recipes, Set<String> pantry) {
        List<Recipe> result = new ArrayList<>(recipes);
        if (!pantry.isEmpty()) result.sort((a, b) -> Integer.compare(pantryMatches(b, pantry).size(), pantryMatches(a, pantry).size()));
        return result;
    }

    public static JSONObject generate(List<Recipe> recipes, Options options, Random random) throws JSONException {
        options.validate();
        List<Recipe> available = eligible(recipes, options);
        Map<String, Integer> uses = new HashMap<>();
        JSONArray slots = new JSONArray();
        for (int day = 0; day < options.days; day++) {
            for (String meal : options.meals) {
                JSONArray ids = new JSONArray();
                Set<String> picked = new HashSet<>();
                int count = meal.equals("早餐") ? 1 : options.dishes;
                for (int dish = 0; dish < count; dish++) {
                    Recipe recipe = pick(available, meal, dish, picked, uses, options.pantry, random);
                    picked.add(recipe.id);
                    uses.put(recipe.id, uses.getOrDefault(recipe.id, 0) + 1);
                    ids.put(recipe.id);
                }
                slots.put(new JSONObject().put("day", day).put("meal", meal).put("recipeIds", ids));
            }
        }
        return create(options, slots, "规则搭配");
    }

    private static Recipe pick(List<Recipe> recipes, String meal, int dish, Set<String> picked,
                               Map<String, Integer> uses, Set<String> pantry, Random random) {
        List<Recipe> pool = new ArrayList<>();
        List<String> preferred = meal.equals("早餐") ? Arrays.asList("早餐")
                : dish == 0 ? Arrays.asList("荤菜", "水产") : dish == 1 ? Arrays.asList("素菜") : Arrays.asList("汤羹");
        for (Recipe r : recipes) if (!picked.contains(r.id) && preferred.contains(r.category)) pool.add(r);
        if (pool.isEmpty()) {
            for (Recipe r : recipes) if (!picked.contains(r.id) && appropriate(r, meal)) pool.add(r);
        }
        if (pool.isEmpty()) throw new IllegalArgumentException("符合忌口条件的" + meal + "菜谱不足，请减少菜数或调整忌口");
        Collections.shuffle(pool, random);
        pool.sort((a, b) -> {
            int repeated = Integer.compare(uses.getOrDefault(a.id, 0), uses.getOrDefault(b.id, 0));
            return repeated != 0 ? repeated : Integer.compare(pantryMatches(b, pantry).size(), pantryMatches(a, pantry).size());
        });
        return pool.get(0);
    }

    public static boolean appropriate(Recipe recipe, String meal) {
        return meal.equals("早餐") ? Arrays.asList("早餐", "主食").contains(recipe.category)
                : Arrays.asList("荤菜", "素菜", "水产", "汤羹", "主食").contains(recipe.category);
    }

    public static JSONObject create(Options options, JSONArray slots, String mode) throws JSONException {
        return new JSONObject().put("start", options.start.toString()).put("days", options.days)
                .put("people", options.people).put("dishes", options.dishes).put("meals", new JSONArray(options.meals))
                .put("exclude", options.exclude).put("request", options.request).put("pantry", new JSONArray(options.pantry))
                .put("mode", mode).put("slots", slots);
    }

    public static JSONArray validateAiSlots(String response, List<Recipe> recipes, Options options) throws JSONException {
        JSONObject result = parseObject(response);
        JSONArray slots = result.getJSONArray("slots");
        if (slots.length() != options.days * options.meals.size()) throw new JSONException("模型返回的餐次数量不完整，请重试");
        Map<String, Recipe> allowed = new HashMap<>();
        for (Recipe r : eligible(recipes, options)) allowed.put(r.id, r);
        Set<String> seen = new HashSet<>();
        Map<String, JSONObject> bySlot = new HashMap<>();
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.getJSONObject(i);
            int day = slot.getInt("day");
            String meal = slot.getString("meal");
            String key = day + meal;
            if (day < 0 || day >= options.days || !options.meals.contains(meal) || !seen.add(key)) {
                throw new JSONException("模型返回的日期或餐次无效");
            }
            JSONArray ids = slot.getJSONArray("recipeIds");
            int count = meal.equals("早餐") ? 1 : options.dishes;
            if (ids.length() != count) throw new JSONException("模型返回的每餐菜数不符");
            Set<String> distinct = new HashSet<>();
            for (int j = 0; j < ids.length(); j++) {
                Recipe r = allowed.get(ids.getString(j));
                if (r == null || !appropriate(r, meal) || !distinct.add(r.id)) throw new JSONException("模型选中了不存在、重复或不符合忌口的菜谱");
            }
            bySlot.put(key, slot);
        }
        JSONArray ordered = new JSONArray();
        for (int day = 0; day < options.days; day++) for (String meal : options.meals) ordered.put(bySlot.get(day + meal));
        return ordered;
    }

    public static JSONObject parseObject(String response) throws JSONException {
        String content = response.trim();
        if (content.startsWith("```")) {
            int newline = content.indexOf('\n');
            int end = content.lastIndexOf("```");
            if (newline < 0 || end <= newline) throw new JSONException("模型返回格式无效");
            content = content.substring(newline + 1, end).trim();
        }
        return new JSONObject(content);
    }

    public static List<ShoppingItem> shopping(JSONObject plan, Map<String, Recipe> recipes, int onlyDay) throws JSONException {
        return shopping(plan, recipes, onlyDay, new JSONObject());
    }

    public static List<ShoppingItem> shopping(JSONObject plan, Map<String, Recipe> recipes, int onlyDay, JSONObject rules) throws JSONException {
        Map<String, ShoppingItem> merged = new LinkedHashMap<>();
        Set<String> completed = new HashSet<>();
        JSONArray done = plan.optJSONArray("completedShopping");
        if (done != null) for (int i = 0; i < done.length(); i++) completed.add(done.getString(i));
        int people = plan.getInt("people");
        JSONArray slots = plan.getJSONArray("slots");
        for (int s = 0; s < slots.length(); s++) {
            JSONObject slot = slots.getJSONObject(s);
            if (onlyDay >= 0 && slot.getInt("day") != onlyDay) continue;
            JSONArray ids = slot.getJSONArray("recipeIds");
            for (int r = 0; r < ids.length(); r++) {
                Recipe recipe = recipes.get(ids.getString(r));
                if (recipe == null) throw new JSONException("菜单中的菜谱已不存在");
                double scale = (double) people / recipe.servings;
                for (int index = 0; index < recipe.ingredients.size(); index++) {
                    Recipe.Ingredient ingredient = recipe.ingredients.get(index);
                    String sourceKey = java.util.UUID.nameUUIDFromBytes(new JSONArray()
                            .put(plan.optString("start")).put(people).put(slot.getInt("day")).put(slot.getString("meal"))
                            .put(recipe.id).put(r).put(index).put(recipe.json.getJSONArray("ingredients").get(index))
                            .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    if (completed.contains(sourceKey)) continue;
                    JSONObject rule = rules.optJSONObject(ingredient.name);
                    String name = rule == null ? normalizeName(ingredient.name) : normalizeName(rule.getString("name"));
                    String category = rule == null ? IngredientCatalog.category(name) : rule.getString("category");
                    String unit = normalizeUnit(ingredient.unit);
                    double amount = ingredient.amount * scale * unitFactor(ingredient.unit);
                    ShoppingItem item = merged.get(name);
                    if (item == null) {
                        item = new ShoppingItem(name, category);
                        merged.put(name, item);
                    }
                    if (amount > 0) item.quantities.put(unit, item.quantities.getOrDefault(unit, 0.0) + amount);
                    item.estimated |= recipe.estimated;
                    item.dishes.add(recipe.name);
                    item.sourceNames.add(ingredient.name);
                    item.sourceKeys.add(sourceKey);
                    if (!ingredient.note.trim().isEmpty()) item.notes.add(recipe.name + "：" + ingredient.note);
                    if (amount == 0) {
                        item.notes.add(recipe.name + "：" + (ingredient.note.trim().isEmpty() ? ingredient.name + "，原方未标明用量" : ingredient.note));
                        item.unmeasuredCount++;
                    }
                }
            }
        }
        List<ShoppingItem> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> Integer.compare(SHOPPING_CATEGORIES.indexOf(a.category), SHOPPING_CATEGORIES.indexOf(b.category)));
        return result;
    }

    static String normalizeName(String name) {
        return IngredientCatalog.normalize(name);
    }

    public static JSONObject parseIngredientRules(String response, Set<String> originals) throws JSONException {
        JSONArray items = parseObject(response).getJSONArray("items");
        JSONObject result = new JSONObject();
        Map<String, String> categories = new HashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String original = item.getString("original");
            String name = normalizeName(item.getString("name"));
            String category = item.getString("category");
            if (!originals.contains(original) || result.has(original) || name.isEmpty() || name.length() > 60
                    || !SHOPPING_CATEGORIES.contains(category)) throw new JSONException("食材整理结果无效");
            if (categories.containsKey(name) && !categories.get(name).equals(category)) throw new JSONException("同种食材分类不一致");
            categories.put(name, category);
            result.put(original, new JSONObject().put("name", name).put("category", category));
        }
        if (result.length() != originals.size()) throw new JSONException("食材整理结果不完整");
        return result;
    }
    static String normalizeUnit(String unit) {
        switch (unit.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "g": case "克": case "kg": case "千克": case "公斤": case "斤": case "两": return "克";
            case "ml": case "毫升": case "l": case "升": return "毫升";
            default: return unit.trim();
        }
    }
    static double unitFactor(String unit) {
        switch (unit.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "kg": case "千克": case "公斤": case "l": case "升": return 1000;
            case "斤": return 500;
            case "两": return 50;
            default: return 1;
        }
    }
    public static final class ShoppingItem {
        public final String name, category;
        public final Map<String, Double> quantities = new LinkedHashMap<>();
        public final Set<String> dishes = new java.util.LinkedHashSet<>(), notes = new java.util.LinkedHashSet<>();
        public final Set<String> sourceNames = new java.util.LinkedHashSet<>(), sourceKeys = new java.util.LinkedHashSet<>();
        public int unmeasuredCount;
        public boolean estimated;
        ShoppingItem(String name, String category) { this.name = name; this.category = category; }
        public String quantity() {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Double> entry : quantities.entrySet()) parts.add(Recipe.number(entry.getValue()) + entry.getKey());
            if (unmeasuredCount > 0) parts.add("待确认 " + unmeasuredCount + " 处");
            return String.join(" + ", parts);
        }
        public String key() { return name + "|" + String.join(",", sourceKeys); }
    }
}
