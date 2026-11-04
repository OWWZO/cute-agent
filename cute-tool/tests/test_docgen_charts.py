import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

from cute_tool.docgen.charts import render_chart_png
from cute_tool.docgen.model import ChartBlock, ChartSeries
from cute_tool.docgen.themes import get_theme


class DocumentChartRenderTest(unittest.TestCase):

    def test_should_render_chinese_pie_chart_without_leagent_dependency(self):
        chart = ChartBlock(
            chart_type="pie",
            title="产品结构占比",
            categories=["A产品", "B产品", "C产品", "D产品"],
            series=[ChartSeries(name="产品占比", values=[35, 28, 22, 15])],
        )

        png = render_chart_png(chart, get_theme("professional"))

        self.assertIsNotNone(png)
        self.assertTrue(png.startswith(b"\x89PNG\r\n\x1a\n"))

    def test_generate_chart_closes_figure_when_savefig_fails(self):
        from matplotlib import pyplot as plt
        from cute_tool.tool.docgen.chart_generator import generate_chart

        params = {
            "chart_type": "bar",
            "data": {
                "categories": ["A", "B"],
                "series": [{"name": "series", "values": [1, 2]}],
            },
        }
        before = len(plt.get_fignums())

        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "chart.png"
            with patch.object(plt, "savefig", side_effect=RuntimeError("save failed")):
                with self.assertRaises(RuntimeError):
                    generate_chart(params, output_path)

        self.assertEqual(before, len(plt.get_fignums()))


if __name__ == "__main__":
    unittest.main()
