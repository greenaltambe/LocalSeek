import struct
import unittest

import lsh_header


class LshHeaderTest(unittest.TestCase):
    def test_decode(self):
        raw = struct.pack(">8i", 1, 10, 9, 64, 100, 0, 1, 3)
        h = lsh_header.decode_header(raw)
        self.assertEqual((h["numTables"], h["numHashBits"], h["vectorCount"]), (10, 9, 3))
        self.assertEqual(h["memoryModeName"], "IN_MEMORY")
        self.assertEqual(h["expectedBytes"], 32 + 3 * (8 + 4 * 384))

    def test_short(self):
        with self.assertRaises(ValueError):
            lsh_header.decode_header(b"\x00" * 10)


if __name__ == "__main__":
    unittest.main()
