#!/usr/bin/env python3
"""
Test script for LZW compression with LRU policy.
Generates test sequences and runs compression/decompression tests.
"""

import random
import subprocess
import os
import sys

def generate_test_sequences():
    """Generate various test sequences to test LRU implementation."""
    test_cases = {
        "empty": "",
        "single_a": "a",
        "single_b": "b",
        "alternating_ab": "ab" * 50,
        "aaaaabbbbbb": "aaaaabbbbbb",
        "all_a": "a" * 50,
        "all_b": "b" * 50,
        "random_short": "".join(random.choice("ab") for _ in range(20)),
        "random_medium": "".join(random.choice("ab") for _ in range(100)),
        "repeating_pattern": "aababb" * 10,
        "mixed_pattern": "abbaaaabbbbaa" * 5,
        # --- Edge cases ---
        "long_run_a_63": "a" * 63,
        "long_run_b_64": "b" * 64,
        "alternating_3a3b": ("aaa" + "bbb") * 20,
        "pattern_abbbbaaa": ("abbbbaaa" * 15),
        "random_long": "".join(random.choice("ab") for _ in range(500)),
        "pattern_cross_boundary": "a"*7 + "b"*9 + "a"*15,
        "sequence_edge_case": "a"*3 + "b"*3 + "a"*4 + "b"*5,
        # Additional edge cases
        "very_long": "".join(random.choice("ab") for _ in range(1000)),
        "repeating_ab_pattern": "ababab" * 50,
        "complex_pattern": "aabbaabbaabb" * 20,
        "stress_test": "".join(random.choice("ab") for _ in range(2000)),
    }
    return test_cases

def create_alphabet_file():
    """Create a simple binary alphabet file with 'a' and 'b'."""
    with open("alphabet_ab.txt", "w") as f:
        f.write("a\n")
        f.write("b\n")

def run_test(test_name, input_data, policy="lru", minW=9, maxW=12):
    """
    Run a single compression/decompression test.

    Args:
        test_name: Name of the test case
        input_data: Input string to compress
        policy: Eviction policy to use (lru, freeze, reset)
        minW: Minimum codeword width
        maxW: Maximum codeword width

    Returns:
        (success, message) tuple
    """
    # Create input file
    input_file = f"test_input_{test_name}.txt"
    compressed_file = f"test_compressed_{test_name}.bin"
    decompressed_file = f"test_decompressed_{test_name}.txt"

    try:
        # Write input data
        with open(input_file, "w") as f:
            f.write(input_data)

        # Compile Java files
        compile_result = subprocess.run(
            ["javac", "LZWTool.java", "TSTmod.java", "BinaryStdIn.java", "BinaryStdOut.java"],
            capture_output=True,
            text=True
        )
        if compile_result.returncode != 0:
            return False, f"Compilation failed: {compile_result.stderr}"

        # Compress
        with open(input_file, "r") as input_f, open(compressed_file, "wb") as output_f:
            compress_result = subprocess.run(
                ["java", "LZWTool", "--mode", "compress", "--minW", str(minW),
                 "--maxW", str(maxW), "--policy", policy, "--alphabet", "alphabet_ab.txt"],
                stdin=input_f,
                stdout=output_f,
                stderr=subprocess.PIPE,
                text=False
            )

        if compress_result.returncode != 0:
            return False, f"Compression failed: {compress_result.stderr.decode()}"

        # Decompress
        with open(compressed_file, "rb") as input_f, open(decompressed_file, "w") as output_f:
            decompress_result = subprocess.run(
                ["java", "LZWTool", "--mode", "expand"],
                stdin=input_f,
                stdout=output_f,
                stderr=subprocess.PIPE,
                text=False
            )

        if decompress_result.returncode != 0:
            return False, f"Decompression failed: {decompress_result.stderr.decode()}"

        # Compare original and decompressed
        with open(decompressed_file, "r") as f:
            decompressed_data = f.read()

        if input_data == decompressed_data:
            # Calculate compression ratio
            original_size = len(input_data)
            compressed_size = os.path.getsize(compressed_file)
            ratio = (compressed_size / original_size * 100) if original_size > 0 else 0
            return True, f"Success! Compression ratio: {ratio:.2f}%"
        else:
            return False, f"Mismatch! Original length: {len(input_data)}, Decompressed length: {len(decompressed_data)}"

    except Exception as e:
        return False, f"Exception: {str(e)}"

    finally:
        # Clean up test files
        for f in [input_file, compressed_file, decompressed_file]:
            if os.path.exists(f):
                os.remove(f)

def run_all_tests():
    """Run all test cases."""
    print("=" * 70)
    print("LRU Policy Test Suite for LZW Compression")
    print("=" * 70)

    # Create alphabet file
    create_alphabet_file()

    # Generate test cases
    test_cases = generate_test_sequences()

    passed = 0
    failed = 0

    for test_name, input_data in test_cases.items():
        print(f"\nTesting: {test_name} (length: {len(input_data)})")
        success, message = run_test(test_name, input_data)

        if success:
            print(f"  ✓ {message}")
            passed += 1
        else:
            print(f"  ✗ {message}")
            failed += 1

    print("\n" + "=" * 70)
    print(f"Test Results: {passed} passed, {failed} failed out of {passed + failed} total")
    print("=" * 70)

    # Clean up alphabet file
    if os.path.exists("alphabet_ab.txt"):
        os.remove("alphabet_ab.txt")

    return failed == 0

if __name__ == "__main__":
    success = run_all_tests()
    sys.exit(0 if success else 1)
