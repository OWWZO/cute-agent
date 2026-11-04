import sqlite3
import unittest
from unittest.mock import patch

from cute_tool.tool.dataprep.sql_query import SQLQueryTool


class SQLQueryResourceCleanupTest(unittest.TestCase):
    def test_connection_is_closed_when_query_execution_fails(self):
        connections = []
        real_connect = sqlite3.connect

        def connect_in_memory(database):
            connection = real_connect(database)
            connections.append(connection)
            return connection

        with (
            patch("sqlite3.connect", side_effect=connect_in_memory),
            patch("pandas.read_sql_query", side_effect=RuntimeError("query failed")),
        ):
            with self.assertRaises(RuntimeError):
                SQLQueryTool().execute_sync(
                    {
                        "query": "SELECT * FROM data",
                        "data": [{"value": 1}],
                    },
                    None,
                )

        self.assertEqual(1, len(connections))
        with self.assertRaises(sqlite3.ProgrammingError):
            connections[0].execute("SELECT 1")


if __name__ == "__main__":
    unittest.main()
