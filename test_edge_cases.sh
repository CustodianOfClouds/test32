#!/bin/bash

# Test all possible edge cases and bad inputs

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=====================================${NC}"
echo -e "${BLUE}Testing Edge Cases & Bad Inputs${NC}"
echo -e "${BLUE}=====================================${NC}\n"

test_count=0
pass_count=0

run_test() {
    local test_name="$1"
    local expected_result="$2"  # "fail" or "pass"
    shift 2
    local cmd="$@"

    test_count=$((test_count + 1))
    echo -e "${BLUE}Test $test_count: $test_name${NC}"

    eval "$cmd" > /tmp/test_out.txt 2>&1
    result=$?

    if [ "$expected_result" = "fail" ]; then
        if [ $result -ne 0 ]; then
            echo -e "${GREEN}✓ Correctly failed${NC}"
            echo "  Error: $(head -1 /tmp/test_out.txt)"
            pass_count=$((pass_count + 1))
        else
            echo -e "${RED}✗ Should have failed but didn't${NC}"
        fi
    else
        if [ $result -eq 0 ]; then
            echo -e "${GREEN}✓ Passed${NC}"
            pass_count=$((pass_count + 1))
        else
            echo -e "${RED}✗ Should have passed but failed${NC}"
            echo "  Error: $(head -1 /tmp/test_out.txt)"
        fi
    fi
    echo ""
}

# 1. Missing arguments
run_test "No arguments" "fail" \
    "java LZWTool"

run_test "Missing --mode" "fail" \
    "echo 'test' | java LZWTool --alphabet alphabets/ab.txt"

run_test "Missing --alphabet for compress" "fail" \
    "echo 'test' | java LZWTool --mode compress --minW 3 --maxW 4"

# 2. Invalid arguments
run_test "Invalid --mode value" "fail" \
    "echo 'test' | java LZWTool --mode invalid --alphabet alphabets/ab.txt"

run_test "Unknown argument" "fail" \
    "echo 'test' | java LZWTool --mode compress --foobar test"

run_test "minW > maxW" "fail" \
    "echo 'a' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 10 --maxW 5"

run_test "Non-numeric minW" "fail" \
    "echo 'a' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW abc --maxW 5"

# 3. File issues
run_test "Non-existent alphabet file" "fail" \
    "echo 'a' | java LZWTool --mode compress --alphabet /nonexistent/file.txt --minW 3 --maxW 4"

run_test "Empty alphabet file" "fail" \
    "touch /tmp/empty_alphabet.txt && echo 'a' | java LZWTool --mode compress --alphabet /tmp/empty_alphabet.txt --minW 3 --maxW 4"

# 4. Alphabet validation
run_test "Character not in alphabet (char 'c' not in ab.txt)" "fail" \
    "echo 'abc' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 4"

run_test "Binary data with text alphabet" "fail" \
    "head -c 100 /dev/urandom | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 4"

run_test "JPEG with binary alphabet (ab.txt)" "fail" \
    "cat TestFiles/frosty.jpg | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 4"

# 5. Empty/minimal inputs
run_test "Empty input (compress)" "pass" \
    "echo -n '' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 4 > /tmp/empty.lzw"

run_test "Single character input" "pass" \
    "echo -n 'a' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 4 | java LZWTool --mode expand > /tmp/single.out && cmp -s <(echo -n 'a') /tmp/single.out"

# 6. Corrupted compressed data
run_test "Corrupt compressed file (random bytes)" "fail" \
    "head -c 50 /dev/urandom | java LZWTool --mode expand"

run_test "Truncated compressed file" "fail" \
    "echo 'aaabbb' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 4 | head -c 10 | java LZWTool --mode expand"

# 7. Very large minW/maxW values
run_test "maxW too large (>32)" "pass" \
    "echo 'a' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 2 --maxW 255"

run_test "minW = 0" "pass" \
    "echo 'a' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 0 --maxW 4"

# 8. Special characters in input
run_test "Null bytes in input (if in alphabet)" "fail" \
    "printf 'a\x00b' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 4"

run_test "Newlines and special chars (should work with ascii.txt)" "pass" \
    "printf 'hello\nworld\ttab\rcarriage' | java LZWTool --mode compress --alphabet alphabets/ascii.txt --minW 9 --maxW 16 | java LZWTool --mode expand > /tmp/special.out && cmp -s <(printf 'hello\nworld\ttab\rcarriage') /tmp/special.out"

# 9. Policy-specific tests
run_test "Invalid policy name" "pass" \
    "echo 'aaa' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 4 --policy invalidpolicy | java LZWTool --mode expand"

# 10. Extremely long input
run_test "Very long input (100KB of 'a's)" "pass" \
    "head -c 100000 /dev/zero | tr '\0' 'a' | java LZWTool --mode compress --alphabet alphabets/ab.txt --minW 3 --maxW 16 | java LZWTool --mode expand | wc -c | grep -q 100000"

# Summary
echo -e "${BLUE}=====================================${NC}"
echo -e "${BLUE}Summary: $pass_count/$test_count tests passed${NC}"
echo -e "${BLUE}=====================================${NC}"

# Cleanup
rm -f /tmp/test_out.txt /tmp/empty_alphabet.txt /tmp/empty.lzw /tmp/single.out /tmp/special.out

if [ $pass_count -eq $test_count ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed!${NC}"
    exit 1
fi
