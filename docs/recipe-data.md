# Offline recipe data

The bundled `recipes.json` is generated from the recipe Markdown files in [Anduin2017/HowToCook](https://github.com/Anduin2017/HowToCook). The source project uses the [Unlicense](https://github.com/Anduin2017/HowToCook/blob/master/LICENSE); its license text is retained beside the Android assets. Each imported entry keeps its original Markdown, the source path, and a link to the exact source commit.

Run `python scripts/import-recipes.py` to regenerate the data from the cached source checkout. Add `--refresh` to fetch the current `master` commit first. The script needs Python 3 and Git, and uses no Python packages. It caches the sparse recipe-only checkout in the system temporary directory; pass `--cache PATH` to choose another location or `--output PATH` to write a separate JSON file.

The generated file contains the repository's cookable dish Markdown with ingredient and numbered cooking sections. It leaves out condiment, beverage, template, and semi-finished folders. `recipe-source.json` records the pinned commit used by the bundled data.

Quantities are read only from the source recipe's `## 计算` section. Simple single values with units are parsed. Unclear quantities, ranges, optional alternatives, and approximate text keep their source wording in `note` and use `amount: 0`; the importer does not infer a shopping quantity from cooking steps. Ingredients from the required-ingredients section that do not have a matching calculation entry are also retained without a quantity.

Serving counts are read only when the source states them. A stated range uses its midpoint and sets `servingsEstimated: true`. Entries without an explicit count default to 2 and set that flag. The recipe time is an estimate from the source introduction when it can be recognized, otherwise 30 minutes. These are planning aids; review each recipe's original instructions and ingredient notes before cooking or shopping.
