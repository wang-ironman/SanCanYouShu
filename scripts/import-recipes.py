#!/usr/bin/env python3
"""Import offline recipes from Anduin2017/HowToCook using Python's standard library."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unicodedata
from collections import Counter
from pathlib import Path
from urllib.parse import quote


REPOSITORY = "https://github.com/Anduin2017/HowToCook.git"
BASE_URL = "https://github.com/Anduin2017/HowToCook/blob"
EXCLUDED = {"condiment", "drink", "template", "semi-finished"}
CATEGORIES = {
    "aquatic": "水产",
    "breakfast": "早餐",
    "meat_dish": "荤菜",
    "soup": "汤羹",
    "staple": "主食",
    "vegetable_dish": "素菜",
    "dessert": "其他",
}
INGREDIENT_CATEGORIES = {
    "蔬果": ("菜", "瓜", "茄", "椒", "葱", "姜", "蒜", "菇", "菌", "豆角", "豆芽", "萝卜", "土豆", "薯", "番茄", "西红柿", "黄瓜", "芹", "笋", "白菜", "菠菜", "玉米", "莲藕", "南瓜", "莴笋", "韭菜", "洋葱", "木耳", "香菜", "花生"),
    "肉蛋水产": ("肉", "鸡", "鸭", "鹅", "牛", "羊", "猪", "排骨", "肠", "蛋", "鱼", "虾", "蟹", "贝", "蚝", "鱿", "蛙", "海鲜", "火腿", "培根", "腊肉"),
    "米面粮油": ("米", "面", "粉", "饼", "馒头", "包子", "馄饨", "饺子", "食用油", "菜籽油", "玉米油", "花生油", "香油", "芝麻油", "植物油", "年糕", "面包", "燕麦", "豆腐", "豆皮", "腐竹", "淀粉", "糯米", "面筋"),
}
UNIT = r"(?:kg|g|克|千克|斤|两|毫升|ml|l|升|个|只|条|根|片|瓣|颗|粒|把|勺|汤匙|茶匙|碗|杯|块|朵|段|张|枚|束|包|盒|袋|滴)"
NUMBER = r"(?:\d+\s*/\s*\d+|\d+(?:\.\d+)?|半|[一二三四五六七八九十]+)"
QUANTITY_RE = re.compile(
    rf"(?<![\w.])(?P<number>{NUMBER})\s*(?P<unit>{UNIT})(?![\w])",
    re.IGNORECASE,
)
RANGE_RE = re.compile(rf"{NUMBER}\s*{UNIT}?\s*(?:-|~|～|至|到)\s*{NUMBER}\s*{UNIT}", re.IGNORECASE)


def run_git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], check=True, text=True,
        encoding="utf-8", stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout.strip()


def open_source(cache: Path, refresh: bool) -> tuple[Path, str]:
    git_dir = cache / ".git"
    if not git_dir.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["git", "clone", "--depth", "1", "--filter=blob:none", "--sparse", REPOSITORY, str(cache)],
            check=True,
        )
    elif refresh:
        subprocess.run(["git", "-C", str(cache), "fetch", "--depth", "1", "origin", "master"], check=True)
        run_git(cache, "reset", "--hard", "FETCH_HEAD")
    subprocess.run(
        ["git", "-C", str(cache), "sparse-checkout", "set", "--no-cone", "dishes/**/*.md"],
        check=True,
    )
    return cache, run_git(cache, "rev-parse", "HEAD")


def get_section(text: str, titles: tuple[str, ...]) -> str:
    lines = text.splitlines()
    start = -1
    for index, line in enumerate(lines):
        match = re.match(r"^##\s+(.+?)\s*#*\s*$", line)
        if match and any(title in match.group(1) for title in titles):
            start = index + 1
            break
    if start < 0:
        return ""
    collected = []
    for line in lines[start:]:
        if re.match(r"^##\s+", line):
            break
        collected.append(line)
    return "\n".join(collected)


def visible_line(line: str) -> str:
    line = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", line)
    line = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", line)
    line = re.sub(r"[`*_~]+", "", line)
    return re.sub(r"\s+", " ", line).strip(" \t-*,;，。；")


def parse_amount(text: str) -> tuple[float, str, str] | None:
    text = text.replace("＊", " ")
    range_match = RANGE_RE.search(text)
    match = QUANTITY_RE.search(text)
    if not match:
        return None
    if range_match and range_match.start() <= match.start():
        return None
    number = match.group("number")
    numbers = {"半": 0.5, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
    value = numbers.get(number)
    if value is None:
        if "/" in number:
            numerator, denominator = number.replace(" ", "").split("/", 1)
            value = float(numerator) / float(denominator)
        else:
            value = float(number)
    unit = match.group("unit").lower()
    if unit == "kg":
        value, unit = value * 1000, "g"
    elif unit in {"克", "千克"}:
        if unit == "千克":
            value *= 1000
        unit = "g"
    elif unit in {"ml", "毫升"}:
        unit = "ml"
    elif unit in {"l", "升"}:
        value, unit = value * 1000, "ml"
    return value, unit, match.group(0)


def ingredient_category(name: str) -> str:
    if name.strip() in {"油", "植物油"}:
        return "米面粮油"
    for category in ("蔬果", "肉蛋水产", "米面粮油"):
        if any(word in name for word in INGREDIENT_CATEGORIES[category]):
            return category
    return "调味干货"


def clean_name(text: str) -> str:
    text = visible_line(text)
    text = re.sub(r"^[*+\d.、)）\s]+", "", text)
    text = re.split(r"[=＝]", text, maxsplit=1)[0]
    text = re.sub(r"^(?:主料|辅料|配料|调料|腌料|蔬菜|肉类|海鲜|材料|食材|工具|器具)\s*[:：]\s*", "", text)
    text = re.split(r"[：:]", text, maxsplit=1)[0]
    # Quantities are recorded in their own fields; leave units and descriptors out of names.
    text = RANGE_RE.sub("", text)
    text = QUANTITY_RE.sub("", text)
    text = re.sub(r"[（(][^）)]*[）)]", "", text)
    text = re.split(r"(?:\s|[，,;；])(?:备注|注意)\s*[:：]", text, maxsplit=1)[0]
    text = text.strip(" ：:，,、；;。（）()")
    return text


def name_key(name: str) -> str:
    normalized = unicodedata.normalize("NFKC", name).casefold()
    normalized = re.sub(r"\([^)]*\)|（[^）]*）", "", normalized)
    return re.sub(r"[\s\W]+", "", normalized)


def ingredient_keys(name: str) -> set[str]:
    key = name_key(name)
    aliases = {
        "猪五花肉": "五花肉", "带皮五花肉": "五花肉", "大肉": "五花肉", "五花肉": "五花肉",
        "生姜": "姜", "姜片": "姜", "生姜片": "姜",
        "大蒜": "蒜", "蒜头": "蒜", "蒜瓣": "蒜",
        "小葱": "葱", "大葱": "葱", "葱花": "葱",
        "食用油": "油",
        "西红柿": "番茄", "番茄": "番茄",
        "马铃薯": "土豆", "土豆": "土豆",
        "生米": "大米", "米": "大米", "大米": "大米",
        "凉水": "水", "开水": "水", "清水": "水", "饮用水": "水", "水": "水",
    }
    return {key, aliases.get(key, key)}


def split_required(line: str) -> list[str]:
    line = visible_line(line)
    line = re.sub(r"^\d+[.)、]\s*", "", line)
    parts = []
    start = 0
    depth = 0
    opening = "（("
    closing = "）)"
    for index, character in enumerate(line):
        if character in opening:
            depth += 1
        elif character in closing and depth:
            depth -= 1
        elif character in "、，,；;" and depth == 0:
            parts.append(line[start:index])
            start = index + 1
    parts.append(line[start:])
    return [clean_name(part) for part in parts if clean_name(part)]


def servings_for(section: str) -> tuple[int, bool]:
    if re.search(r"每[一二三四五六七八九十]?份|每人", section):
        return 1, False
    patterns = (
        re.compile(r"(?:适合|够|供|供\s*)\s*(\d+)\s*(?:至|到|[-~～])\s*(\d+)\s*人"),
        re.compile(r"(?:适合|用于|以下用量适合|版本|每次)[^\n。]{0,20}?(\d+)\s*至\s*(\d+)\s*人"),
        re.compile(r"(?:^|[\s，。])(\d+)\s*个?\s*人(?:食用|份|吃|量)"),
    )
    for pattern in patterns:
        match = pattern.search(section)
        if not match:
            continue
        if len(match.groups()) == 2:
            return max(1, round((int(match.group(1)) + int(match.group(2))) / 2)), True
        if len(match.groups()) == 1 and match.group(1):
            return max(1, int(match.group(1))), False
        if "每份" in match.group(0) or "每人" in match.group(0) or "每个份量" in match.group(0):
            return 1, False
    return 2, True


def estimate_minutes(text: str) -> int:
    match = re.search(r"(?:全程|从备料到出锅|制作时长|制作时间)[^\n。]{0,25}?(\d+(?:\.\d+)?)\s*(?:-|–|至|到)\s*(\d+(?:\.\d+)?)?\s*(分钟|分|小时|时)", text)
    if not match:
        match = re.search(r"(?:全程|从备料到出锅|制作时长|制作时间)[^\n。]{0,25}?(\d+(?:\.\d+)?)\s*(分钟|分|小时|时)", text)
        if not match:
            return 30
        duration = float(match.group(1))
        unit = match.group(2)
    else:
        duration = float(match.group(2) or match.group(1))
        unit = match.group(3)
    return max(5, min(300, round(duration * (60 if unit in {"小时", "时"} else 1))))


def parse_steps(text: str) -> list[str]:
    operation = get_section(text, ("操作", "做法", "制作步骤", "具体步骤"))
    steps = []
    for line in operation.splitlines():
        if re.match(r"^\s*(?:\d+[.、)]|[*+-])\s+", line):
            step = visible_line(re.sub(r"^\s*(?:\d+[.、)]|[*+-])\s+", "", line))
            if len(step) > 1:
                steps.append(step)
    return steps


def parse_ingredients(text: str) -> list[dict]:
    required = get_section(text, ("必备原料", "必需原料", "主要材料", "所需材料"))
    parsed = []
    for line in required.splitlines():
        if not re.match(r"^\s*[-*+]\s+", line):
            continue
        plain_line = visible_line(line)
        if re.search(r"^(?:注|注意|备注|工具|器具)\s*[:：]|如果有可能|请尽量|这道菜|仅作参考", plain_line):
            continue
        for name in split_required(line):
            key = name_key(name)
            if not key or re.search(r"^注|^备注|如果|步骤|器具|厨具|锅$|碗$|菜刀|量杯|厨房秤|平底锅|炒锅|烤箱|空气炸锅|砧板|滤网|剪刀|打蛋器|锅具", name):
                continue
            parsed.append({"name": name, "amount": 0.0, "unit": "", "note": visible_line(line), "category": ingredient_category(name)})

    # Calculation lines only enrich an ingredient that the required-ingredients list names.
    # This filters explanation, arithmetic, tools, and cooking-time formulae from shopping.
    calculation = get_section(text, ("计算", "用量"))
    for line in calculation.splitlines():
        if not re.match(r"^\s*(?:[-*+]\s+|\d+[.、)]\s+)", line):
            continue
        label = clean_name(line.split("=", 1)[0].split("＝", 1)[0])
        key = name_key(label)
        if not key or re.search(r"一般|可以食用|体积|容量|时间|总量|份数|计算|上述|变量|T1|Tr|Mo|Tc|器具|用法", label, re.IGNORECASE):
            continue
        item = next((candidate for candidate in parsed if ingredient_keys(candidate["name"]) & ingredient_keys(label)), None)
        if item is None:
            continue
        match = QUANTITY_RE.search(line)
        amount = parse_amount(line) if match and not re.search(r"[/／]", line[match.end():]) else None
        if amount and not re.search(r"向上取整|进位", line):
            item["amount"], item["unit"], _ = amount
            if re.search(r"可选|选填|[（(]|份数", line):
                item["note"] = visible_line(line)
            else:
                item["note"] = ""
        else:
            item["amount"], item["unit"], item["note"] = 0.0, "", visible_line(line)
    unique = {}
    for item in parsed:
        key = name_key(item["name"])
        if key and key not in unique:
            unique[key] = item
    return list(unique.values())


def title_for(text: str, path: str) -> str:
    for line in text.splitlines():
        match = re.match(r"^#\s+(.+?)\s*(?:的做法|做法|制作方法)?\s*$", line)
        if match:
            name = visible_line(match.group(1))
            name = re.sub(r"的做法$|做法$|制作方法$", "", name).strip()
            if name:
                return name
    return Path(path).stem


def category_for(path: str) -> str:
    folder = path.split("/", 2)[1]
    if Path(path).stem.endswith("粥"):
        return "早餐"
    return CATEGORIES.get(folder, "其他")


def parse_recipe(path: Path, root: Path, revision: str) -> dict | None:
    relative = path.relative_to(root).as_posix()
    top = relative.split("/", 2)[1]
    if top in EXCLUDED or top not in CATEGORIES:
        return None
    raw = path.read_text(encoding="utf-8").strip()
    if not raw:
        return None
    raw = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", raw)
    ingredients = parse_ingredients(raw)
    steps = parse_steps(raw)
    if not ingredients or not steps:
        return None
    servings, estimated = servings_for(get_section(raw, ("计算", "用量")))
    encoded_path = quote(relative, safe="/")
    return {
        "id": "htc-" + hashlib.sha1(relative.encode("utf-8")).hexdigest()[:14],
        "name": title_for(raw, relative),
        "category": category_for(relative),
        "servings": servings,
        "servingsEstimated": estimated,
        "minutes": estimate_minutes(raw),
        "ingredients": ingredients,
        "steps": steps,
        "source": f"{BASE_URL}/{revision}/{encoded_path}",
        "sourcePath": relative,
        "raw": raw,
        "custom": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh", action="store_true", help="fetch the current master commit before importing")
    parser.add_argument("--cache", type=Path, help="local sparse Git checkout cache")
    parser.add_argument("--output", type=Path, help="recipe JSON destination")
    args = parser.parse_args()

    project = Path(__file__).resolve().parents[1]
    cache = args.cache or Path(tempfile.gettempdir()) / "caidan-howtocook-source"
    output = args.output or project / "android/app/src/main/assets/recipes.json"
    try:
        root, revision = open_source(cache, args.refresh)
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        print(f"Source checkout failed: {error}", file=sys.stderr)
        return 2

    files = sorted((root / "dishes").rglob("*.md"))
    recipes = []
    for path in files:
        try:
            recipe = parse_recipe(path, root, revision)
        except (OSError, UnicodeError) as error:
            print(f"Skipping {path}: {error}", file=sys.stderr)
            continue
        if recipe:
            recipes.append(recipe)
    recipes.sort(key=lambda recipe: (recipe["category"], recipe["name"].casefold(), recipe["sourcePath"]))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(recipes, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    metadata = {
        "repository": "https://github.com/Anduin2017/HowToCook",
        "revision": revision,
        "license": "Unlicense",
        "generatedBy": "scripts/import-recipes.py",
        "recipeCount": len(recipes),
        "excludedFolders": sorted(EXCLUDED),
    }
    metadata_path = project / "android/app/src/main/assets/recipe-source.json"
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Imported {len(recipes)} recipes from {revision}")
    print("Categories:", json.dumps(dict(Counter(recipe["category"] for recipe in recipes)), ensure_ascii=False))
    print(f"Unknown ingredient quantities: {sum(item['amount'] == 0 for recipe in recipes for item in recipe['ingredients'])}")
    print(f"Output: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
