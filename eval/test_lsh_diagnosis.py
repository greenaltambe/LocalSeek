import random
import struct
import unittest

import lsh_diagnosis as L


def f32bits(x):
    return struct.unpack(">i", struct.pack(">f", x))[0]


class LshDiagnosisTest(unittest.TestCase):
    def test_kotlin_random_matches_stdlib(self):
        # golden values from kotlin-stdlib 2.3.21: Random(42).nextFloat()*2f-1f (float bits), 5 values, then after 1000 more draws
        r = L.KotlinRandom(42)
        got = [f32bits(r.next_float() * 2.0 - 1.0) for _ in range(5)]
        self.assertEqual(got, [-1089724312, -1102969088, 1062164094, 1064472162, -1091910084])
        for _ in range(1000):
            r.next_float()
        self.assertEqual(f32bits(r.next_float() * 2.0 - 1.0), -1119913408)

    def test_candidate_order_and_cap(self):
        t0 = {1: [0, 1, 2], 0: [3]}       # base bucket 1 then probe flips bit 0 -> bucket 0
        t1 = {1: [2, 4], 3: [5]}
        seq = L.collect([t0, t1], [1, 1], 2, None)
        self.assertEqual(seq, [0, 1, 2, 3, 4, 5])
        self.assertEqual(L.collect([t0, t1], [1, 1], 2, 4), [0, 1, 2, 3])            # cap keeps insertion order
        rr = L.collect([t0, t1], [1, 1], 2, 4, "round_robin")
        self.assertEqual(rr, [0, 2, 1, 4])                                            # interleaved across tables

    def test_hash_and_tables(self):
        planes = [[[1.0] + [0.0] * 383, [0.0, 1.0] + [0.0] * 382]]
        v_pos = [1.0, -1.0] + [0.0] * 382
        h = L._hash_slice(([v_pos], planes))[0][0]
        self.assertEqual(h, 0b01)
        tabs = L.build_tables([[0b01], [0b11]], 1, 1)
        self.assertEqual(tabs[0], {1: [0, 1]})

    def test_top_k_tie_rule(self):
        self.assertEqual(L.top_k([(5, 0.5), (2, 0.5), (9, 0.9)], 2), [(9, 0.9), (2, 0.5)])


if __name__ == "__main__":
    unittest.main()
