#!/usr/bin/env python3
"""
Test script to verify all policies (freeze, reset, lru) work correctly.
"""

import subprocess
import os

def create_alphabet_file():
    """Create a simple binary alphabet file with 'a' and 'b'."""
    with open("alphabet_ab.txt", "w") as f:
        f.write("a\n")
        f.write("b\n")

def test_policy(policy_name, input_data="aabbaabbaabb" * 20):
    """Test a specific policy."""
    input_file = f"test_{policy_name}_input.txt"
    compressed_file = f"test_{policy_name}_compressed.bin"
    decompressed_file = f"test_{policy_name}_decompressed.txt"

    try:
        # Write input data
        with open(input_file, "w") as f:
            f.write(input_data)

        # Compile
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
                ["java", "LZWTool", "--mode", "compress", "--minW", "9",
                 "--maxW", "12", "--policy", policy_name, "--alphabet", "alphabet_ab.txt"],
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

        # Compare
        with open(decompressed_file, "r") as f:
            decompressed_data = f.read()

        if input_data == decompressed_data:
            compressed_size = os.path.getsize(compressed_file)
            ratio = (compressed_size / len(input_data) * 100) if len(input_data) > 0 else 0
            return True, f"Success! Compression ratio: {ratio:.2f}%"
        else:
            return False, f"Mismatch! Original: {len(input_data)}, Decompressed: {len(decompressed_data)}"

    except Exception as e:
        return False, f"Exception: {str(e)}"

    finally:
        for f in [input_file, compressed_file, decompressed_file]:
            if os.path.exists(f):
                os.remove(f)

def main():
    print("=" * 70)
    print("Testing All Policies")
    print("=" * 70)

    create_alphabet_file()

    policies = ["freeze", "reset", "lru"]
    results = {}

    for policy in policies:
        print(f"\nTesting policy: {policy}")
        success, message = test_policy(policy)
        results[policy] = success

        if success:
            print(f"  ✓ {message}")
        else:
            print(f"  ✗ {message}")

    # Clean up
    if os.path.exists("alphabet_ab.txt"):
        os.remove("alphabet_ab.txt")

    print("\n" + "=" * 70)
    print("Summary:")
    for policy, success in results.items():
        status = "✓ PASSED" if success else "✗ FAILED"
        print(f"  {policy}: {status}")
    print("=" * 70)

    return all(results.values())

if __name__ == "__main__":
    import sys
    success = main()
    sys.exit(0 if success else 1)
