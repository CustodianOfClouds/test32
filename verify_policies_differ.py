#!/usr/bin/env python3
"""
Verify that RESET, FREEZE, LRU, and LFU all produce different compression ratios
on very large random tests
"""

import os
import sys
import subprocess
import random
import tempfile

def compress_test(input_data, alphabet_path, minW, maxW, policy):
    """Compress and return the compressed size"""
    with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.txt') as f:
        f.write(input_data)
        input_file = f.name

    compressed_file = input_file + '.lzw'

    try:
        # Compress
        with open(input_file, 'r') as infile, open(compressed_file, 'wb') as outfile:
            result = subprocess.run([
                "java", "LZWTool",
                "--mode", "compress",
                "--minW", str(minW),
                "--maxW", str(maxW),
                "--policy", policy,
                "--alphabet", alphabet_path
            ], stdin=infile, stdout=outfile, stderr=subprocess.PIPE, text=False)

            if result.returncode != 0:
                print(f"Compression failed for {policy}: {result.stderr.decode()}")
                return None

        compressed_size = os.path.getsize(compressed_file)
        return compressed_size

    finally:
        for f in [input_file, compressed_file]:
            if os.path.exists(f):
                os.remove(f)

print("Verifying all 4 policies differ on very large random tests\n")

# Set seed for reproducibility
random.seed(42)

# Test different alphabets with very large random data
test_configs = [
    ("ab", ["a", "b"], "alphabets/ab.txt", 2, 4, 10000),
    ("abracadabra", ["A", "B", "R", "C", "D"], "alphabets/abracadabra.txt", 3, 5, 10000),
    ("tobeornot", ["T", "O", "B", "E", "R", "N"], "alphabets/tobeornot.txt", 3, 5, 10000),
]

policies = ["freeze", "reset", "lru", "lfu"]

print("="*80)
for name, chars, alphabet_file, minW, maxW, length in test_configs:
    print(f"\nAlphabet: {name} ({len(chars)} chars) - {length} random characters")
    print(f"minW={minW}, maxW={maxW}")
    print("-"*80)

    # Generate very large random sequence
    random_data = "".join(random.choice(chars) for _ in range(length))

    results = {}
    for policy in policies:
        size = compress_test(random_data, alphabet_file, minW, maxW, policy)
        if size:
            ratio = size / length
            results[policy] = {"size": size, "ratio": ratio}
            print(f"  {policy.upper():7s}: {size:6d} bytes, ratio: {ratio:.6f}")

    # Check if all are different
    print()
    ratios = [results[p]["ratio"] for p in policies if p in results]
    if len(set(ratios)) == 4:
        print(f"  ✓ ALL 4 POLICIES PRODUCE DIFFERENT RESULTS")
    else:
        print(f"  ✗ Some policies produce the same results:")
        for p1 in policies:
            for p2 in policies:
                if p1 < p2 and p1 in results and p2 in results:
                    if results[p1]["ratio"] == results[p2]["ratio"]:
                        print(f"    {p1} == {p2}")

    # Show differences
    if "freeze" in results:
        print(f"\n  Differences from FREEZE:")
        for policy in ["reset", "lru", "lfu"]:
            if policy in results:
                diff = results[policy]["ratio"] - results["freeze"]["ratio"]
                pct = (diff / results["freeze"]["ratio"]) * 100
                sign = "+" if diff > 0 else ""
                print(f"    {policy.upper():7s}: {sign}{diff:.6f} ({sign}{pct:.2f}%)")

    print("="*80)

print("\nDone!")
