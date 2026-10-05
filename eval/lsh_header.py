"""Decode ONLY the header of the app's lsh_index.bin (DataOutputStream, big-endian).

Layout (LshIndexManager.saveIndex): int version, numTables, numHashBits, projectionDim,
searchCandidates, memoryMode ordinal (0 IN_MEMORY, 1 STREAMING), probeRadius, vectorCount,
then vectorCount x (long chunkId, 384 x float32). The generation is NOT persisted.
Prints no vector data and no ids.
"""
import os
import struct
import sys

EMBEDDING_DIM = 384
FIELDS = ("version", "numTables", "numHashBits", "projectionDim", "searchCandidates",
          "memoryMode", "probeRadius", "vectorCount")
MODES = {0: "IN_MEMORY", 1: "STREAMING"}


def decode_header(data: bytes) -> dict:
    if len(data) < 4 * len(FIELDS):
        raise ValueError("file too short for header")
    h = dict(zip(FIELDS, struct.unpack(">8i", data[:32])))
    h["memoryModeName"] = MODES.get(h["memoryMode"], "UNKNOWN")
    h["expectedBytes"] = 32 + h["vectorCount"] * (8 + 4 * EMBEDDING_DIM)
    return h


def main(path: str) -> None:
    with open(path, "rb") as f:
        h = decode_header(f.read(32))
    h["actualBytes"] = os.path.getsize(path)
    h["sizeMatches"] = h["actualBytes"] == h["expectedBytes"]
    for k, v in h.items():
        print(f"{k}={v}")


if __name__ == "__main__":
    main(sys.argv[1])
