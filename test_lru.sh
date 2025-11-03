#!/bin/bash

# Test script for LRU eviction policy

# Compile the Java files
echo "Compiling Java files..."
javac LZWTool.java TSTmod.java BinaryStdIn.java BinaryStdOut.java

# Create test alphabets
echo "Creating test alphabets..."

# Test 1: abracadabra alphabet
echo -e "A\nB\nR\nC\nD" > alphabet_abracadabra.txt

# Test 2: ab alphabet
echo -e "a\nb" > alphabet_ab.txt

# Test 1: Random string with abracadabra alphabet
echo "=== Test 1: Random medium string with abracadabra alphabet ==="
python3 -c "
import random
random.seed(42)
chars = ['A', 'B', 'R', 'C', 'D']
s = ''.join(random.choice(chars) for _ in range(100))
print(s, end='')
" > test1_input.txt

echo "Input string (first 50 chars):"
head -c 50 test1_input.txt
echo ""

echo "Compressing with minW=9 (very small to force evictions)..."
cat test1_input.txt | java LZWTool --mode compress --minW 9 --maxW 10 --policy lru --alphabet alphabet_abracadabra.txt > test1_compressed.lzw 2>test1_compress.err

if [ $? -ne 0 ]; then
    echo "Compression failed!"
    cat test1_compress.err
    exit 1
fi

echo "Compressed successfully. Size: $(wc -c < test1_compressed.lzw) bytes"

echo "Decompressing..."
cat test1_compressed.lzw | java LZWTool --mode expand > test1_output.txt 2>test1_expand.err

if [ $? -ne 0 ]; then
    echo "Decompression failed!"
    cat test1_expand.err
    exit 1
fi

echo "Decompressed successfully."

echo "Comparing original and decompressed..."
if diff -q test1_input.txt test1_output.txt > /dev/null; then
    echo "✓ Test 1 PASSED: Files match!"
else
    echo "✗ Test 1 FAILED: Files differ!"
    echo "First 50 chars of original:"
    head -c 50 test1_input.txt
    echo ""
    echo "First 50 chars of output:"
    head -c 50 test1_output.txt
    echo ""
fi

echo ""
echo "=== Test 2: Alternating ab string ==="

python3 -c "print('ab' * 50, end='')" > test2_input.txt

echo "Input string (first 50 chars):"
head -c 50 test2_input.txt
echo ""

echo "Compressing with minW=9 (very small to force evictions)..."
cat test2_input.txt | java LZWTool --mode compress --minW 9 --maxW 10 --policy lru --alphabet alphabet_ab.txt > test2_compressed.lzw 2>test2_compress.err

if [ $? -ne 0 ]; then
    echo "Compression failed!"
    cat test2_compress.err
    exit 1
fi

echo "Compressed successfully. Size: $(wc -c < test2_compressed.lzw) bytes"

echo "Decompressing..."
cat test2_compressed.lzw | java LZWTool --mode expand > test2_output.txt 2>test2_expand.err

if [ $? -ne 0 ]; then
    echo "Decompression failed!"
    cat test2_expand.err
    exit 1
fi

echo "Decompressed successfully."

echo "Comparing original and decompressed..."
if diff -q test2_input.txt test2_output.txt > /dev/null; then
    echo "✓ Test 2 PASSED: Files match!"
else
    echo "✗ Test 2 FAILED: Files differ!"
    echo "First 50 chars of original:"
    head -c 50 test2_input.txt
    echo ""
    echo "First 50 chars of output:"
    head -c 50 test2_output.txt
    echo ""
fi

echo ""
echo "All tests completed."
