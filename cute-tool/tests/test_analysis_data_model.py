# -*- coding: utf-8 -*-
import unittest

import pandas as pd

from cute_tool.tool.analysis_component.data_model import DataModel, Measure


def build_data_model() -> DataModel:
    return DataModel(
        data=pd.DataFrame({"category": ["a", "b"], "value": [3, 4]}),
        measure=Measure(name="value", column="value", type="quantity", agg="sum"),
    )


class DataModelIdentityTest(unittest.TestCase):
    def test_default_ids_should_differ_between_instances(self):
        first = build_data_model()
        second = build_data_model()

        self.assertNotEqual(first.id, second.id)

    def test_data_model_should_hash_to_int(self):
        model = build_data_model()

        self.assertIsInstance(hash(model), int)
        self.assertEqual(1, len({model}))


if __name__ == "__main__":
    unittest.main()
