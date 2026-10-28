package cn.caidan.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative offline ingredient-name cleanup and shopping category inference. */
public final class IngredientCatalog {
    private static final Pattern PAREN_NOTE = Pattern.compile("（[^）]*）|\\([^)]*\\)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Set<String> SEASONINGS = words(
            "盐 食盐 精盐 白砂糖 白糖 糖 冰糖 红糖 生抽 老抽 酱油 醋 陈醋 香醋 米醋 料酒 黄酒 米酒\n" +
            "鱼露 蚝油 虾酱 鸡精 味精 豆瓣酱 番茄酱 蒜蓉酱 甜面酱 黄豆酱 甜辣酱\n" +
            "姜粉 洋葱粉 蒜粉 花椒 胡椒 白胡椒 黑胡椒 五香粉 十三香 孜然 辣椒粉 辣椒面 咖喱粉\n" +
            "豆豉 腐乳 芝麻酱 芝麻油 香油 辣椒油 花椒油 藤椒油 辣椒酱 豆瓣 辣酱" );
    private static final Set<String> GRAINS = words(
            "大米 白米 米 小米 糯米 黑米 紫米 燕麦 荞麦 藜麦 玉米面 玉米粒\n" +
            "面条 挂面 米粉 粉丝 粉条 河粉 面包 馒头 包子 饺子皮 春卷皮\n" +
            "面粉 糯米粉 澄粉 淀粉 土豆淀粉 玉米淀粉 红薯淀粉 木薯淀粉 生粉\n" +
            "食用油 菜籽油 花生油 玉米油 大豆油 橄榄油 猪油 黄油 椰子油" );
    private static final Set<String> MEAT_FISH = words(
            "猪肉 五花肉 排骨 肋排 猪排 猪蹄 猪肝 猪心 猪血 猪油 牛肉 牛腩 牛腱 牛排\n" +
            "羊肉 羊排 鸡肉 鸡腿 鸡翅 鸡胸 鸡爪 鸭肉 鸭腿 鸭翅 鸭血 鹌鹑肉\n" +
            "鸡蛋 鸭蛋 鹌鹑蛋 皮蛋 咸蛋 鸡蛋清 鸡蛋黄 鱼 虾 蟹 贝 蚝 鱿鱼 墨鱼\n" +
            "鲈鱼 鲫鱼 草鱼 鲤鱼 鲢鱼 鳕鱼 三文鱼 罗非鱼 黄鱼 带鱼 鳗鱼 鲳鱼\n" +
            "豆腐 豆干 豆皮 腐竹 豆浆 牛奶 奶油 奶酪 芝士 酸奶" );
    private static final Set<String> VEGETABLES = words(
            "土豆 番茄 西红柿 洋葱 大葱 小葱 葱 姜 蒜 大蒜 韭菜 韭黄 芹菜 香菜\n" +
            "白菜 娃娃菜 包菜 卷心菜 生菜 菠菜 油菜 菜心 空心菜 茼蒿 西兰花 花菜\n" +
            "黄瓜 冬瓜 南瓜 丝瓜 苦瓜 茄子 青椒 尖椒 彩椒 辣椒 胡萝卜 白萝卜\n" +
            "莲藕 山药 莴笋 芦笋 竹笋 豆角 四季豆 荷兰豆 豌豆 毛豆 玉米\n" +
            "香菇 金针菇 金菇 平菇 杏鲍菇 木耳 银耳 海带 紫菜 豆芽 黄豆芽 绿豆芽\n" +
            "苹果 梨 香蕉 橙子 橘子 柠檬 草莓 蓝莓 葡萄 桃子 菠萝 芒果 小米椒 朝天椒 干辣椒" );
    private static final Set<String> PROCESSED_GRAINS = words(
            "粉条 粉丝 米粉 河粉 面条 挂面 面粉 糯米粉 玉米淀粉 红薯淀粉 土豆淀粉\n" +
            "木薯淀粉 淀粉 生粉 澄粉 土豆粉" );

    private IngredientCatalog() { }

    public static String normalize(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        int fullWidthOpen = trimmed.indexOf('（');
        int asciiOpen = trimmed.indexOf('(');
        int open = fullWidthOpen < 0 ? asciiOpen : asciiOpen < 0 ? fullWidthOpen : Math.min(fullWidthOpen, asciiOpen);
        if (open >= 0) trimmed = trimmed.substring(0, open);
        String value = WHITESPACE.matcher(PAREN_NOTE.matcher(trimmed).replaceAll(" "))
                .replaceAll(" ").trim();
        if (value.isEmpty()) return value;

        if (value.equals("生姜片") || value.equals("姜片") || value.equals("姜丝") || value.equals("姜末")) return "姜";
        if (value.equals("生姜") || value.equals("生姜末") || value.equals("生姜丝") || value.equals("老姜") || value.equals("姜")) return "姜";
        if (value.equals("蒜片") || value.equals("蒜末") || value.equals("蒜蓉") || value.equals("蒜头") ||
                value.equals("蒜仔") || value.equals("大蒜") || value.equals("蒜瓣")) return "蒜";
        if (value.equals("小葱花") || value.equals("香葱") || value.equals("小香葱") || value.equals("青葱") ||
                value.equals("葱花") || value.equals("葱末") || value.equals("葱段") || value.equals("葱结") ||
                value.equals("葱")) return "小葱";
        if (value.equals("大葱葱白")) return "大葱";
        if (value.equals("西红柿")) return "番茄";
        if (value.equals("马铃薯")) return "土豆";
        if (value.equals("食盐") || value.equals("精盐")) return "盐";
        if (value.equals("白糖")) return "白砂糖";
        if (value.equals("金菇")) return "金针菇";
        if (value.equals("活虾") || value.equals("大虾") || value.equals("海虾") || value.equals("鲜虾")) return "虾";
        return value;
    }

    public static String category(String name) {
        String value = normalize(name);
        if (value.isEmpty()) return "其他";
        if (containsSeasoning(value)) return "调味干货";
        if (contains(value, PROCESSED_GRAINS)) return "米面粮油";
        if (contains(value, MEAT_FISH)) return "肉蛋水产";
        if (contains(value, VEGETABLES)) return "蔬果";
        if (contains(value, GRAINS)) return "米面粮油";
        return "其他";
    }

    private static boolean containsSeasoning(String value) {
        for (String word : SEASONINGS) if (value.contains(word)) return true;
        return false;
    }

    private static boolean contains(String value, Set<String> words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private static Set<String> words(String values) {
        return new HashSet<>(Arrays.asList(values.trim().split("\\s+")));
    }
}
