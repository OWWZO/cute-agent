# -*- coding: utf-8 -*-
import unittest

import pandas as pd

from cute_tool.tool.analysis_component.data_model import (
    Column,
    DataModel,
    Measure,
    SiblingGroup,
)
from cute_tool.tool.analysis_component.insights import OutstandingLastInsightType


class OutstandingLastInsightTest(unittest.TestCase):
    def test_outstanding_last_should_significance_test_last_value(self):
        df = pd.DataFrame(
            {
                "region": [f"region-{i}" for i in range(8)],
                "profit": [10.0, 8.0, 6.0, 5.0, 4.0, 3.0, 2.0, -20.0],
            }
        )
        data_model = DataModel(
            data=df,
            measure=Measure(
                name="profit", column="profit", type="quantity", agg="sum"
            ),
        )
        group = SiblingGroup(
            data=data_model,
            breakdown=Column(name="region"),
        )

        insight = OutstandingLastInsightType.from_data(group, threshold=0.0)

        self.assertIsNotNone(insight)
        self.assertEqual("OutstandingLast", insight.type)
        self.assertEqual("region-7", insight.insight["min_value"]["region"])
        self.assertGreaterEqual(1.0, insight.significance)
        self.assertLessEqual(0.0, insight.significance)


if __name__ == "__main__":
    unittest.main()
