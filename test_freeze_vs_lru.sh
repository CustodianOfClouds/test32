#!/bin/bash

# Test to verify freeze vs lru produce different compression sizes

javac LZWTool.java TSTmod.java BinaryStdIn.java BinaryStdOut.java

echo "=== Testing if freeze vs lru produce different compression sizes ==="
echo ""

# Create test data
echo -e "a\nb" > alphabet_ab.txt
python3 -c "print('ab' * 50, end='')" > test_input.txt

echo "Input: abababab... (100 chars total)"
echo "Input size: $(wc -c < test_input.txt) bytes"
echo ""

# Test with freeze policy
echo "Compressing with FREEZE policy (minW=3, maxW=4)..."
cat test_input.txt | java LZWTool --mode compress --minW 3 --maxW 4 --policy freeze --alphabet alphabet_ab.txt > freeze_compressed.lzw 2>&1
freeze_size=$(wc -c < freeze_compressed.lzw)
echo "Freeze compressed size: $freeze_size bytes"

# Verify freeze decompression works
cat freeze_compressed.lzw | java LZWTool --mode expand > freeze_output.txt 2>&1
if diff -q test_input.txt freeze_output.txt > /dev/null; then
    echo "✓ Freeze decompression: PASSED"
else
    echo "✗ Freeze decompression: FAILED"
fi

echo ""

# Test with lru policy
echo "Compressing with LRU policy (minW=3, maxW=4)..."
cat test_input.txt | java LZWTool --mode compress --minW 3 --maxW 4 --policy lru --alphabet alphabet_ab.txt > lru_compressed.lzw 2>&1
lru_size=$(wc -c < lru_compressed.lzw)
echo "LRU compressed size: $lru_size bytes"

# Verify lru decompression works
cat lru_compressed.lzw | java LZWTool --mode expand > lru_output.txt 2>&1
if diff -q test_input.txt lru_output.txt > /dev/null; then
    echo "✓ LRU decompression: PASSED"
else
    echo "✗ LRU decompression: FAILED"
fi

echo ""
echo "========================================="
if [ "$freeze_size" -eq "$lru_size" ]; then
    echo "WARNING: Freeze and LRU produce SAME size!"
    echo "This suggests LRU is NOT evicting entries."
    echo "The implementation is likely BROKEN."
else
    echo "SUCCESS: Freeze ($freeze_size bytes) vs LRU ($lru_size bytes)"
    echo "LRU is working correctly (different compression)."
fi
echo "========================================="

# More comprehensive test
echo ""
echo "=== Testing with different dictionary sizes ==="
for maxW in 3 4 5; do
    echo ""
    echo "Testing maxW=$maxW..."

    cat test_input.txt | java LZWTool --mode compress --minW 3 --maxW $maxW --policy freeze --alphabet alphabet_ab.txt > freeze_$maxW.lzw 2>&1
    freeze_size=$(wc -c < freeze_$maxW.lzw)

    cat test_input.txt | java LZWTool --mode compress --minW 3 --maxW $maxW --policy lru --alphabet alphabet_ab.txt > lru_$maxW.lzw 2>&1
    lru_size=$(wc -c < lru_$maxW.lzw)

    echo "  Freeze: $freeze_size bytes, LRU: $lru_size bytes"

    if [ "$freeze_size" -eq "$lru_size" ]; then
        echo "  ⚠ SAME SIZE - LRU not working!"
    else
        diff=$((lru_size - freeze_size))
        echo "  ✓ Different (LRU is ${diff:+$diff} bytes difference)"
    fi
done
