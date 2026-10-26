"""Check the shipped pizza models and recipe, including their finished states."""
import json
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parent
MODELS = ROOT / "Nexo/pack/assets/sf/models/block"
SAUCE = "sf:pies/cranberry_filled_top"


def load_model(name):
    return json.loads((MODELS / f"{name}.json").read_text())


def top_texture(model, element):
    texture = element.get("faces", {}).get("up", {}).get("texture", "")
    while texture.startswith("#"):
        texture = model["textures"][texture[1:]]
    return texture


def filling_surfaces(model, texture):
    return [element for element in model["elements"]
            if top_texture(model, element) == texture
            and element["to"][0] - element["from"][0] > 1
            and element["to"][2] - element["from"][2] > 1]


class PizzaModelsTest(unittest.TestCase):
    def test_ingredient_layers_stay_inside_the_crust_and_below_the_rim(self):
        heights = []
        for name, texture in (("spinach", "sf:food/pizza_spinach"),
                              ("cheese", "sf:food/pizza_cheese"),
                              ("sauce_topped", SAUCE)):
            with self.subTest(stage=name):
                model = load_model(f"deep_dish_pizza_{name}")
                surfaces = filling_surfaces(model, texture)
                self.assertTrue(surfaces, "the ingredient needs a visible surface")
                height = max(element["to"][1] for element in surfaces)
                heights.append(height)
                self.assertGreater(height, 1, "filling must sit above the crust floor")
                self.assertLess(height, 4, "the rim must remain above the filling")
                for element in surfaces:
                    for axis in (0, 2):
                        self.assertGreaterEqual(element["from"][axis], 3)
                        self.assertLessEqual(element["to"][axis], 13)
        self.assertEqual(heights, sorted(set(heights)), "each added layer raises the filling")
        self.assertLess(heights[0], 2, "spinach should only barely fill the base")

    def test_finished_and_sliced_pizzas_keep_the_open_cranberry_sauce_top(self):
        for state in ("cold", "baked"):
            areas = []
            for bites in range(4):
                suffix = "" if not bites else f"_slice{bites}"
                with self.subTest(state=state, bites=bites):
                    model = load_model(f"{state}_deep_dish_pizza{suffix}")
                    self.assertNotIn("pumpkin", json.dumps(model))
                    self.assertTrue(all(top_texture(model, element) == SAUCE
                                        for element in model["elements"]
                                        if "up" in element["faces"] and element["to"][1] > 1))
                    surfaces = filling_surfaces(model, SAUCE)
                    self.assertTrue(surfaces)
                    self.assertTrue(all(element["to"][1] < 4 for element in surfaces))
                    areas.append(sum((element["to"][0] - element["from"][0])
                                     * (element["to"][2] - element["from"][2]) for element in surfaces))
            self.assertTrue(all(before > after for before, after in zip(areas, areas[1:])),
                            "each bite should remove part of the sauce and crust")

    def test_recipe_finishes_after_sauce_with_two_incomplete_models(self):
        config = yaml.safe_load((ROOT / "kitchen-additions.yml").read_text())
        pizza = config["pies"]["types"]["deep_dish_pizza"]
        self.assertEqual([step["item"] for step in pizza["steps"]],
                         ["nexo:spinach", "nexo:cheese", "nexo:tomato_sauce"])
        self.assertEqual(pizza["progress-models"],
                         ["nexo:deep_dish_pizza_spinach", "nexo:deep_dish_pizza_cheese"])


if __name__ == "__main__":
    unittest.main()
