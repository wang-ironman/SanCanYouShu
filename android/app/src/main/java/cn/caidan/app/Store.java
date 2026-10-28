package cn.caidan.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Store {
    private final Context context;
    private final SharedPreferences prefs;
    public final List<Recipe> recipes = new ArrayList<>();
    public final Map<String, Recipe> byId = new LinkedHashMap<>();
    public Set<String> favorites, checked, pantry;
    public JSONObject plan;
    public JSONObject ingredientRules;

    public Store(Context context) throws Exception {
        this.context = context;
        prefs = context.getSharedPreferences("kitchen", Context.MODE_PRIVATE);
        favorites = new HashSet<>(prefs.getStringSet("favorites", new HashSet<>()));
        checked = new HashSet<>(prefs.getStringSet("checked", new HashSet<>()));
        pantry = new java.util.TreeSet<>(prefs.getStringSet("pantry", new HashSet<>()));
        loadRecipes();
        String saved = read("plan.json");
        if (!saved.trim().isEmpty()) plan = new JSONObject(saved);
        String rules = read("ingredient-rules.json");
        ingredientRules = rules.trim().isEmpty() ? new JSONObject() : new JSONObject(rules);
    }

    private void loadRecipes() throws Exception {
        recipes.clear(); byId.clear();
        try (InputStream input = context.getAssets().open("recipes.json"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            append(new JSONArray(output.toString("UTF-8")));
        }
        String custom = read("custom.json");
        if (!custom.trim().isEmpty()) append(new JSONArray(custom));
    }
    private void append(JSONArray values) throws Exception {
        for (int i = 0; i < values.length(); i++) {
            Recipe recipe = new Recipe(values.getJSONObject(i));
            if (byId.put(recipe.id, recipe) != null) throw new IllegalStateException("菜谱编号重复");
            recipes.add(recipe);
        }
    }
    private String read(String name) throws Exception {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), name));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return "";
        return new String(file.readFully(), StandardCharsets.UTF_8);
    }
    private void write(String name, String value) throws Exception {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), name));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            throw e;
        }
    }
    public void savePlan(JSONObject value) throws Exception {
        write("plan.json", value.toString());
        plan = value;
        checked.clear();
        prefs.edit().putStringSet("checked", checked).apply();
    }
    public boolean prunePastMenus(java.time.LocalDate today) throws Exception {
        if (plan == null) return false;
        int first = Planner.firstActiveDay(plan, today);
        if (first == 0) return false;
        JSONArray slots = plan.getJSONArray("slots"), kept = new JSONArray();
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.getJSONObject(i);
            if (slot.getInt("day") >= first) kept.put(slot);
        }
        if (kept.length() == slots.length() && kept.length() > 0) return false;
        JSONObject value = kept.length() == 0 ? null : new JSONObject(plan.toString()).put("slots", kept);
        Set<String> validChecks = new HashSet<>();
        if (value != null) {
            for (Planner.ShoppingItem item : Planner.shopping(value, byId, -1, ingredientRules)) validChecks.add(item.key());
            for (int day = first; day < value.getInt("days"); day++) {
                for (Planner.ShoppingItem item : Planner.shopping(value, byId, day, ingredientRules)) validChecks.add(item.key());
            }
        }
        write("plan.json", value == null ? "" : value.toString());
        plan = value;
        checked.retainAll(validChecks);
        prefs.edit().putStringSet("checked", checked).commit();
        return true;
    }
    public void saveRecipe(Recipe recipe) throws Exception {
        if (!recipe.custom || !recipe.id.startsWith("custom_")) throw new IllegalArgumentException("只能编辑自建菜谱");
        JSONArray values = new JSONArray();
        for (Recipe item : recipes) if (item.custom && !item.id.equals(recipe.id)) values.put(item.json);
        values.put(recipe.json);
        write("custom.json", values.toString());
        loadRecipes();
        checked.clear();
        prefs.edit().putStringSet("checked", checked).apply();
    }
    public void deleteRecipe(Recipe recipe) throws Exception {
        if (!recipe.custom) throw new IllegalArgumentException("基础菜谱不能删除");
        if (plan != null && plan.getJSONArray("slots").toString().contains("\"" + recipe.id + "\"")) {
            throw new IllegalArgumentException("这道菜已在当前菜单中，请先换菜后再删除");
        }
        JSONArray values = new JSONArray();
        for (Recipe item : recipes) if (item.custom && !item.id.equals(recipe.id)) values.put(item.json);
        write("custom.json", values.toString());
        loadRecipes();
        favorites.remove(recipe.id);
        prefs.edit().putStringSet("favorites", favorites).apply();
    }
    public void favorite(String id) {
        if (!favorites.add(id)) favorites.remove(id);
        prefs.edit().putStringSet("favorites", favorites).apply();
    }
    public void check(String key, boolean value) {
        if (value) checked.add(key); else checked.remove(key);
        prefs.edit().putStringSet("checked", checked).apply();
    }
    public void savePantry(Set<String> names) {
        Set<String> normalized = new java.util.TreeSet<>();
        for (String name : names) {
            String value = Planner.normalizeName(name);
            if (!value.isEmpty()) normalized.add(value);
        }
        if (!prefs.edit().putStringSet("pantry", normalized).commit()) {
            throw new IllegalStateException("已有食材保存失败，请检查设备存储空间");
        }
        pantry = normalized;
    }

    public List<Planner.ShoppingItem> shoppingItems(int day) throws Exception {
        return plan == null ? new ArrayList<>() : Planner.shopping(plan, byId, day, ingredientRules);
    }
    public void selectShopping(int day, boolean selected) throws Exception {
        for (Planner.ShoppingItem item : shoppingItems(day)) {
            if (selected) checked.add(item.key()); else checked.remove(item.key());
        }
        prefs.edit().putStringSet("checked", checked).commit();
    }
    public int completeShopping(int day) throws Exception {
        Set<String> completed = new java.util.LinkedHashSet<>();
        JSONArray previous = plan.optJSONArray("completedShopping");
        if (previous != null) for (int i = 0; i < previous.length(); i++) completed.add(previous.getString(i));
        Set<String> selected = new HashSet<>();
        for (Planner.ShoppingItem item : shoppingItems(day)) if (checked.contains(item.key())) {
            completed.addAll(item.sourceKeys); selected.add(item.key());
        }
        if (selected.isEmpty()) return 0;
        JSONObject value = new JSONObject(plan.toString()).put("completedShopping", new JSONArray(completed));
        write("plan.json", value.toString()); plan = value;
        checked.removeAll(selected);
        prefs.edit().putStringSet("checked", checked).commit();
        return selected.size();
    }
    public void resetShopping() throws Exception {
        JSONObject value = new JSONObject(plan.toString()); value.remove("completedShopping");
        write("plan.json", value.toString()); plan = value;
        checked.clear(); prefs.edit().putStringSet("checked", checked).commit();
    }
    public void saveIngredientRules(JSONObject rules) throws Exception {
        Set<String> originals = new HashSet<>();
        JSONArray items = new JSONArray();
        java.util.Iterator<String> keys = rules.keys();
        while (keys.hasNext()) {
            String original = keys.next(); originals.add(original);
            JSONObject rule = rules.getJSONObject(original);
            items.put(new JSONObject().put("original", original).put("name", rule.getString("name")).put("category", rule.getString("category")));
        }
        JSONObject validated = Planner.parseIngredientRules(new JSONObject().put("items", items).toString(), originals);
        write("ingredient-rules.json", validated.toString()); ingredientRules = validated;
        checked.clear(); prefs.edit().putStringSet("checked", checked).commit();
    }
}
