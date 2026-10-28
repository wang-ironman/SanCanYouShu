package cn.caidan.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Recipe {
    public final JSONObject json;
    public final String id, name, category;
    public final int servings, minutes;
    public final boolean estimated, custom;
    public final List<Ingredient> ingredients = new ArrayList<>();
    public final List<String> steps = new ArrayList<>();

    public Recipe(JSONObject value) throws JSONException {
        json = value;
        id = value.getString("id");
        name = value.getString("name");
        category = value.optString("category", "其他");
        servings = value.optInt("servings", 2);
        minutes = value.optInt("minutes", 30);
        estimated = value.optBoolean("servingsEstimated", true);
        custom = value.optBoolean("custom", false);
        JSONArray items = value.getJSONArray("ingredients");
        for (int i = 0; i < items.length(); i++) ingredients.add(new Ingredient(items.getJSONObject(i)));
        JSONArray directions = value.getJSONArray("steps");
        for (int i = 0; i < directions.length(); i++) if (!directions.getString(i).trim().isEmpty()) steps.add(directions.getString(i));
        if (id.trim().isEmpty() || name.trim().isEmpty() || servings < 1 || servings > 100 || ingredients.isEmpty() || steps.isEmpty()) {
            throw new JSONException("菜谱需要名称、有效人数、食材和做法");
        }
    }

    public boolean matches(String text) {
        String q = text.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Ingredient i : ingredients) if (i.name.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    public boolean excludes(String[] words) {
        String text = name + " " + json.optString("raw", "") + " " + json.optJSONArray("ingredients") + " " + json.optJSONArray("steps");
        for (String word : words) if (!word.trim().isEmpty() && text.contains(word.trim())) return true;
        return false;
    }

    public static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.00001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static final class Ingredient {
        public String name, unit, note, category;
        public double amount;
        public Ingredient(JSONObject value) throws JSONException {
            name = value.getString("name").trim();
            amount = value.optDouble("amount", 0);
            unit = value.optString("unit", "").trim();
            note = value.optString("note", "");
            category = value.optString("category", "其他");
            if (name.isEmpty() || !Double.isFinite(amount) || amount < 0) throw new JSONException("食材名称或数量无效");
        }
        public String quantity(double scale) { return amount > 0 ? number(amount * scale) + unit : "按原方确认"; }
    }
}
