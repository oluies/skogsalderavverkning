"""Tests for the JSON-stat2 row-major expansion.

flatten() is the load-bearing correctness guarantee for every table in the
pipeline: it pairs cell *i* with the *i*-th tuple of product(*labels). If that
contract slips, no error appears - the rows simply carry the wrong labels, and
the damage only shows up much later as an empty filter result in build.sql.

Run: python3 tests/test_flatten_jsonstat.py
"""
import csv, json, os, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
from flatten_jsonstat import flatten


def doc(size, labels, value):
    """A minimal JSON-stat2 document with `len(size)` dimensions."""
    dims = [f"D{i}" for i in range(len(size))]
    return {
        "id": dims,
        "size": size,
        "dimension": {
            d: {"category": {"index": {k: j for j, k in enumerate(labels[i])},
                             "label": {k: k for k in labels[i]}}}
            for i, d in enumerate(dims)
        },
        "value": value,
    }


def run(document):
    """Flatten a document and return its rows as dicts."""
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "in.json")
        out = os.path.join(tmp, "out.csv")
        with open(src, "w", encoding="utf-8") as fh:
            json.dump(document, fh)
        flatten(src, out)
        with open(out, encoding="utf-8") as fh:
            return list(csv.DictReader(fh))


class TestFlatten(unittest.TestCase):

    def test_row_major_order(self):
        """Cell i must land on the i-th tuple, last dimension varying fastest."""
        rows = run(doc([2, 3], [["a", "b"], ["x", "y", "z"]],
                       [1, 2, 3, 4, 5, 6]))
        self.assertEqual(len(rows), 6)
        got = {(r["D0"], r["D1"]): r["value"] for r in rows}
        self.assertEqual(got, {
            ("a", "x"): "1", ("a", "y"): "2", ("a", "z"): "3",
            ("b", "x"): "4", ("b", "y"): "5", ("b", "z"): "6",
        })

    def test_three_dimensions(self):
        rows = run(doc([2, 2, 2], [["a", "b"], ["c", "d"], ["e", "f"]],
                       list(range(8))))
        got = {(r["D0"], r["D1"], r["D2"]): r["value"] for r in rows}
        self.assertEqual(got[("a", "c", "e")], "0")
        self.assertEqual(got[("a", "d", "e")], "2")
        self.assertEqual(got[("b", "d", "f")], "7")

    def test_sparse_value_dict_blanks_land_on_the_right_rows(self):
        """A sparse value dict must blank the absent cells, not shift the rest."""
        rows = run(doc([2, 2], [["a", "b"], ["x", "y"]], {"0": 10, "3": 40}))
        got = {(r["D0"], r["D1"]): r["value"] for r in rows}
        self.assertEqual(got, {
            ("a", "x"): "10", ("a", "y"): "",
            ("b", "x"): "", ("b", "y"): "40",
        })

    def test_null_values_become_blank(self):
        rows = run(doc([1, 2], [["a"], ["x", "y"]], [None, 5]))
        self.assertEqual([r["value"] for r in rows], ["", "5"])

    def test_short_label_list_raises(self):
        """A dimension with fewer labels than its declared size must fail loudly."""
        d = doc([2, 3], [["a", "b"], ["x", "y", "z"]], list(range(6)))
        d["size"][1] = 4                      # claim four labels where three exist
        with self.assertRaises(ValueError) as cm:
            run(d)
        self.assertIn("label counts", str(cm.exception))

    def test_short_value_array_raises(self):
        d = doc([2, 3], [["a", "b"], ["x", "y", "z"]], list(range(5)))
        with self.assertRaises(ValueError) as cm:
            run(d)
        self.assertIn("expected", str(cm.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
