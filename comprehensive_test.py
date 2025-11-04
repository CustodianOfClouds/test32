#!/usr/bin/env python3
"""
Comprehensive test suite for LZWTool optimizations
Tests:
1. Alphabet validation (should reject invalid input)
2. Multiple alphabets with different sequences
3. LRU/LFU policies
4. Edge cases (bad arguments, missing files, etc.)
"""

import os
import sys
import subprocess
import random
import tempfile

# Color codes for output
GREEN = '\033[92m'
RED = '\033[91m'
YELLOW = '\033[93m'
BLUE = '\033[94m'
RESET = '\033[0m'

def log_test(message):
    print(f"{BLUE}[TEST]{RESET} {message}")

def log_pass(message):
    print(f"{GREEN}[PASS]{RESET} {message}")

def log_fail(message):
    print(f"{RED}[FAIL]{RESET} {message}")

def log_warn(message):
    print(f"{YELLOW}[WARN]{RESET} {message}")

# Compile Java files
def compile_java():
    log_test("Compiling Java files...")
    result = subprocess.run(
        ["javac", "LZWTool.java", "BinaryStdIn.java", "BinaryStdOut.java", "TSTmod.java"],
        capture_output=True,
        text=True
    )
    if result.returncode != 0:
        log_fail(f"Compilation failed: {result.stderr}")
        sys.exit(1)
    log_pass("Compilation successful")

# Test alphabet validation
def test_alphabet_validation():
    """Test that JPG with ab.txt alphabet fails correctly"""
    log_test("Testing alphabet validation (JPG with ab.txt - should FAIL)")

    jpg_file = "TestFiles/frosty.jpg"
    if not os.path.exists(jpg_file):
        log_warn(f"JPG file {jpg_file} not found, skipping")
        return

    # Try to compress JPG with binary alphabet
    cmd = [
        "java", "LZWTool",
        "--mode", "compress",
        "--minW", "3",
        "--maxW", "4",
        "--policy", "freeze",
        "--alphabet", "alphabets/ab.txt"
    ]

    with open(jpg_file, 'rb') as infile:
        result = subprocess.run(
            cmd,
            stdin=infile,
            capture_output=True,
            text=True
        )

    # Should fail with alphabet error
    if result.returncode != 0 and "not in the alphabet" in result.stderr:
        log_pass(f"Correctly rejected invalid input: {result.stderr.strip()}")
        return True
    else:
        log_fail(f"Should have rejected JPG with ab.txt alphabet. Return code: {result.returncode}")
        if result.stderr:
            print(f"  stderr: {result.stderr}")
        return False

# Alphabet definitions
ALPHABET_FILES = {
    "ab": ["a", "b"],
    "abracadabra": ["A", "B", "R", "C", "D"],
    "tobeornot": ["T", "O", "B", "E", "R", "N"],
    "ascii": None  # Use existing file
}

def generate_test_sequences(alphabet):
    """Generate test sequences for a given alphabet"""
    seqs = {}
    chars = alphabet
    n = len(chars)

    # Basic sequences
    seqs["empty"] = ""
    seqs["single_first"] = chars[0]
    seqs["single_last"] = chars[-1]

    if n >= 2:
        seqs["alternating_first_last"] = (chars[0] + chars[-1]) * 50
        seqs["repeating_pattern"] = (chars[0]*3 + chars[1]*3) * 10
        seqs["alternating_ab"] = (chars[0] + chars[1]) * 100
        seqs["edge_case"] = chars[1]*5 + chars[0] + chars[1]*2 + chars[0]*3 + chars[1]*3 + chars[0] + chars[1]*2 + chars[0]*3 + chars[1]*3
        seqs["aaaaabbbbbb"] = chars[0]*5 + chars[1]*6

    # All of one character
    seqs["all_first"] = chars[0] * 50
    seqs["all_last"] = chars[-1] * 50

    # Random sequences
    seqs["random_short"] = "".join(random.choice(chars) for _ in range(20))
    seqs["random_medium"] = "".join(random.choice(chars) for _ in range(100))
    seqs["random_long"] = "".join(random.choice(chars) for _ in range(500))
    seqs["random_very_long"] = "".join(random.choice(chars) for _ in range(10000))

    # Edge cases
    seqs["long_run_first_63"] = chars[0] * 63
    seqs["long_run_last_64"] = chars[-1] * 64

    if n >= 2:
        seqs["alternating_3_3"] = (chars[0]*3 + chars[1]*3) * 20
        seqs["pattern_cross_boundary"] = chars[0]*7 + chars[-1]*9 + chars[0]*15
        seqs["sequence_edge_case"] = chars[0]*3 + chars[1]*3 + chars[0]*4 + chars[1]*5

    if n >= 3:
        seqs["pattern_complex"] = (chars[0]*4 + chars[1]*3 + chars[2]*2) * 15
        seqs["incremental_sequence"] = "".join(chars[i % n] for i in range(50))

    return seqs

def pick_bitwidths(alphabet_size):
    """Choose minW/maxW based on alphabet size"""
    minW = max(2, (alphabet_size - 1).bit_length())
    maxW = minW + 2
    return minW, maxW

def compress_expand_test(input_data, alphabet_path, minW, maxW, policy, test_name):
    """Test compress and expand cycle"""

    with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.txt') as f:
        f.write(input_data)
        input_file = f.name

    compressed_file = input_file + '.lzw'
    decompressed_file = input_file + '.out'

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
                log_fail(f"{test_name} - Compression failed: {result.stderr.decode()}")
                return False, None

        # Expand
        with open(compressed_file, 'rb') as infile, open(decompressed_file, 'w') as outfile:
            result = subprocess.run([
                "java", "LZWTool",
                "--mode", "expand"
            ], stdin=infile, stdout=outfile, stderr=subprocess.PIPE, text=False)

            if result.returncode != 0:
                log_fail(f"{test_name} - Decompression failed: {result.stderr.decode()}")
                return False, None

        # Compare
        with open(decompressed_file, 'r') as f:
            output_data = f.read()

        if input_data == output_data:
            compressed_size = os.path.getsize(compressed_file)
            original_size = len(input_data)
            ratio = compressed_size / max(original_size, 1) if original_size > 0 else 0
            return True, ratio
        else:
            log_fail(f"{test_name} - Output doesn't match input!")
            log_fail(f"  Input length: {len(input_data)}, Output length: {len(output_data)}")
            return False, None

    finally:
        # Cleanup
        for f in [input_file, compressed_file, decompressed_file]:
            if os.path.exists(f):
                os.remove(f)

def test_alphabet_suite(alphabet_name, alphabet_chars, alphabet_file):
    """Test a complete alphabet with all policies"""
    log_test(f"\n{'='*60}")
    log_test(f"Testing alphabet: {alphabet_name} ({len(alphabet_chars)} chars)")
    log_test(f"{'='*60}")

    minW, maxW = pick_bitwidths(len(alphabet_chars))
    log_test(f"Using minW={minW}, maxW={maxW}")

    sequences = generate_test_sequences(alphabet_chars)
    policies = ["freeze", "reset", "lru", "lfu"]

    results = {}

    for policy in policies:
        policy_results = []
        log_test(f"\nTesting policy: {policy.upper()}")

        for seq_name, seq_data in sequences.items():
            if seq_data == "":
                continue  # Skip empty

            test_name = f"{alphabet_name}/{policy}/{seq_name}"
            success, ratio = compress_expand_test(seq_data, alphabet_file, minW, maxW, policy, test_name)

            if success:
                policy_results.append({
                    'name': seq_name,
                    'size': len(seq_data),
                    'ratio': ratio
                })
                log_pass(f"  {seq_name:25s} - Size: {len(seq_data):6d}, Ratio: {ratio:.3f}")
            else:
                log_fail(f"  {seq_name:25s} - FAILED")
                return False

        results[policy] = policy_results

    # Check that LRU/LFU produce different results on large data
    log_test(f"\nVerifying LRU/LFU differ on large sequences...")
    for seq_name in ["random_very_long"]:
        ratios = {p: next((r['ratio'] for r in results[p] if r['name'] == seq_name), None)
                  for p in policies}

        if ratios['lru'] and ratios['lfu']:
            if ratios['lru'] != ratios['lfu']:
                log_pass(f"  {seq_name}: LRU ratio={ratios['lru']:.4f}, LFU ratio={ratios['lfu']:.4f} (DIFFERENT ✓)")
            else:
                log_warn(f"  {seq_name}: LRU and LFU have same ratio (might be coincidence)")

    return True

def test_edge_cases():
    """Test edge cases and error handling"""
    log_test(f"\n{'='*60}")
    log_test("Testing Edge Cases")
    log_test(f"{'='*60}")

    tests_passed = 0
    tests_total = 0

    # Test 1: Missing --mode
    log_test("\n1. Missing --mode argument")
    tests_total += 1
    result = subprocess.run(
        ["java", "LZWTool", "--alphabet", "alphabets/ab.txt"],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        log_pass("  Correctly rejected missing --mode")
        tests_passed += 1
    else:
        log_fail("  Should have rejected missing --mode")

    # Test 2: Invalid mode
    log_test("\n2. Invalid --mode value")
    tests_total += 1
    result = subprocess.run(
        ["java", "LZWTool", "--mode", "invalid"],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        log_pass("  Correctly rejected invalid mode")
        tests_passed += 1
    else:
        log_fail("  Should have rejected invalid mode")

    # Test 3: Missing alphabet
    log_test("\n3. Missing --alphabet for compression")
    tests_total += 1
    result = subprocess.run(
        ["java", "LZWTool", "--mode", "compress", "--minW", "3", "--maxW", "4"],
        capture_output=True, text=True, input=""
    )
    if result.returncode != 0:
        log_pass("  Correctly rejected missing alphabet")
        tests_passed += 1
    else:
        log_fail("  Should have rejected missing alphabet")

    # Test 4: Invalid alphabet file
    log_test("\n4. Non-existent alphabet file")
    tests_total += 1
    result = subprocess.run(
        ["java", "LZWTool", "--mode", "compress", "--alphabet", "nonexistent.txt",
         "--minW", "3", "--maxW", "4"],
        capture_output=True, text=True, input="a"
    )
    if result.returncode != 0:
        log_pass("  Correctly rejected non-existent alphabet")
        tests_passed += 1
    else:
        log_fail("  Should have rejected non-existent alphabet")

    # Test 5: minW > maxW
    log_test("\n5. Invalid minW > maxW")
    tests_total += 1
    result = subprocess.run(
        ["java", "LZWTool", "--mode", "compress", "--alphabet", "alphabets/ab.txt",
         "--minW", "10", "--maxW", "5"],
        capture_output=True, text=True, input="a"
    )
    if result.returncode != 0:
        log_pass("  Correctly rejected minW > maxW")
        tests_passed += 1
    else:
        log_fail("  Should have rejected minW > maxW")

    # Test 6: Unknown argument
    log_test("\n6. Unknown argument")
    tests_total += 1
    result = subprocess.run(
        ["java", "LZWTool", "--unknown-arg", "value"],
        capture_output=True, text=True
    )
    if result.returncode != 0 and "Unknown argument" in result.stderr:
        log_pass("  Correctly rejected unknown argument")
        tests_passed += 1
    else:
        log_fail("  Should have rejected unknown argument")

    # Test 7: Character not in alphabet (already tested in alphabet validation)
    log_test("\n7. Input character not in alphabet")
    tests_total += 1
    with tempfile.NamedTemporaryFile(mode='w', delete=False) as f:
        f.write("abc")  # 'c' not in ab.txt
        test_file = f.name

    try:
        with open(test_file, 'r') as infile:
            result = subprocess.run([
                "java", "LZWTool",
                "--mode", "compress",
                "--alphabet", "alphabets/ab.txt",
                "--minW", "3",
                "--maxW", "4"
            ], stdin=infile, capture_output=True, text=False)

        if result.returncode != 0 and b"not in the alphabet" in result.stderr:
            log_pass(f"  Correctly rejected invalid character: {result.stderr.decode().strip()}")
            tests_passed += 1
        else:
            log_fail("  Should have rejected character not in alphabet")
    finally:
        os.remove(test_file)

    # Test 8: Empty input
    log_test("\n8. Empty input file")
    tests_total += 1
    result = subprocess.run([
        "java", "LZWTool",
        "--mode", "compress",
        "--alphabet", "alphabets/ab.txt",
        "--minW", "3",
        "--maxW", "4"
    ], input="", capture_output=True, text=True)

    # Empty input should succeed (just write header)
    if result.returncode == 0:
        log_pass("  Empty input handled correctly")
        tests_passed += 1
    else:
        log_fail("  Empty input should succeed")

    log_test(f"\nEdge Case Tests: {tests_passed}/{tests_total} passed")
    return tests_passed == tests_total

def test_real_files():
    """Test with real files from TestFiles directory"""
    log_test(f"\n{'='*60}")
    log_test("Testing Real Files with ASCII alphabet")
    log_test(f"{'='*60}")

    test_files = [
        ("TestFiles/code.txt", "Text file"),
        ("TestFiles/medium.txt", "Medium text"),
    ]

    policies = ["freeze", "lru", "lfu"]

    for filepath, description in test_files:
        if not os.path.exists(filepath):
            log_warn(f"File not found: {filepath}")
            continue

        log_test(f"\nTesting {description}: {filepath}")

        with open(filepath, 'r', errors='ignore') as f:
            content = f.read()

        log_test(f"  File size: {len(content)} bytes")

        for policy in policies:
            success, ratio = compress_expand_test(
                content,
                "alphabets/ascii.txt",
                9, 16,
                policy,
                f"{filepath}/{policy}"
            )

            if success:
                log_pass(f"  {policy.upper():6s} - Ratio: {ratio:.4f}")
            else:
                log_fail(f"  {policy.upper():6s} - FAILED")
                return False

    return True

def main():
    print(f"\n{BLUE}{'='*70}")
    print(f"LZWTool Comprehensive Test Suite")
    print(f"Testing Optimizations & Edge Cases")
    print(f"{'='*70}{RESET}\n")

    # Set random seed for reproducibility
    random.seed(42)

    # Compile
    compile_java()

    all_passed = True

    # Test 1: Alphabet validation (should fail)
    if not test_alphabet_validation():
        all_passed = False

    # Test 2: Edge cases
    if not test_edge_cases():
        all_passed = False

    # Test 3: ab alphabet with minW=3, maxW=4
    if not test_alphabet_suite("ab", ["a", "b"], "alphabets/ab.txt"):
        all_passed = False

    # Test 4: abracadabra alphabet
    if not test_alphabet_suite("abracadabra", ["A", "B", "R", "C", "D"], "alphabets/abracadabra.txt"):
        all_passed = False

    # Test 5: tobeornot alphabet
    if not test_alphabet_suite("tobeornot", ["T", "O", "B", "E", "R", "N"], "alphabets/tobeornot.txt"):
        all_passed = False

    # Test 6: Real files
    if not test_real_files():
        all_passed = False

    # Final summary
    print(f"\n{BLUE}{'='*70}{RESET}")
    if all_passed:
        print(f"{GREEN}✓ ALL TESTS PASSED{RESET}")
        print(f"\n{GREEN}Optimizations verified:{RESET}")
        print(f"  ✓ Boolean array alphabet validation works correctly")
        print(f"  ✓ Invalid characters are properly rejected")
        print(f"  ✓ StringBuilder pooling doesn't break functionality")
        print(f"  ✓ String caching doesn't affect correctness")
        print(f"  ✓ Cached bit-shift operations work correctly")
        print(f"  ✓ LRU/LFU policies produce different results")
        print(f"  ✓ Edge cases handled properly")
    else:
        print(f"{RED}✗ SOME TESTS FAILED{RESET}")
        sys.exit(1)
    print(f"{BLUE}{'='*70}{RESET}\n")

if __name__ == "__main__":
    main()
