#!/bin/bash

# More aggressive test with very small dictionary sizes

echo "Compiling Java files..."
javac LZWTool.java TSTmod.java BinaryStdIn.java BinaryStdOut.java

# Create test alphabets
echo -e "A\nB\nR\nC\nD" > alphabet_abracadabra.txt
echo -e "a\nb" > alphabet_ab.txt

# Test with very small maxW to force frequent evictions
echo "=== Test 1: Random string with tiny dictionary (maxW=4) ==="
python3 -c "
import random
random.seed(42)
chars = ['A', 'B', 'R', 'C', 'D']
s = ''.join(random.choice(chars) for _ in range(100))
print(s, end='')
" > test1_input.txt

echo "Compressing with minW=3, maxW=4 (max 16 entries)..."
cat test1_input.txt | java LZWTool --mode compress --minW 3 --maxW 4 --policy lru --alphabet alphabet_abracadabra.txt > test1_compressed.lzw 2>test1_compress.err

if [ $? -ne 0 ]; then
    echo "Compression failed!"
    cat test1_compress.err
else
    echo "Compressed successfully."
    cat test1_compressed.lzw | java LZWTool --mode expand > test1_output.txt 2>test1_expand.err

    if [ $? -ne 0 ]; then
        echo "Decompression failed!"
        cat test1_expand.err
    else
        if diff -q test1_input.txt test1_output.txt > /dev/null; then
            echo "✓ Test 1 PASSED"
        else
            echo "✗ Test 1 FAILED"
            echo "Original (first 100 chars): $(cat test1_input.txt)"
            echo "Output (first 100 chars): $(head -c 100 test1_output.txt)"
        fi
    fi
fi

echo ""
echo "=== Test 2: Alternating ab with tiny dictionary (maxW=4) ==="
python3 -c "print('ab' * 50, end='')" > test2_input.txt

echo "Compressing with minW=3, maxW=4 (max 16 entries)..."
cat test2_input.txt | java LZWTool --mode compress --minW 3 --maxW 4 --policy lru --alphabet alphabet_ab.txt > test2_compressed.lzw 2>test2_compress.err

if [ $? -ne 0 ]; then
    echo "Compression failed!"
    cat test2_compress.err
else
    echo "Compressed successfully."
    cat test2_compressed.lzw | java LZWTool --mode expand > test2_output.txt 2>test2_expand.err

    if [ $? -ne 0 ]; then
        echo "Decompression failed!"
        cat test2_expand.err
    else
        if diff -q test2_input.txt test2_output.txt > /dev/null; then
            echo "✓ Test 2 PASSED"
        else
            echo "✗ Test 2 FAILED"
            echo "Original: $(cat test2_input.txt)"
            echo "Output: $(cat test2_output.txt)"
        fi
    fi
fi

echo ""
echo "=== Test 3: Alternating ab with even tinier dictionary (maxW=3) ==="
python3 -c "print('ab' * 30, end='')" > test3_input.txt

echo "Compressing with minW=3, maxW=3 (max 8 entries)..."
cat test3_input.txt | java LZWTool --mode compress --minW 3 --maxW 3 --policy lru --alphabet alphabet_ab.txt > test3_compressed.lzw 2>test3_compress.err

if [ $? -ne 0 ]; then
    echo "Compression failed!"
    cat test3_compress.err
else
    echo "Compressed successfully."
    cat test3_compressed.lzw | java LZWTool --mode expand > test3_output.txt 2>test3_expand.err

    if [ $? -ne 0 ]; then
        echo "Decompression failed!"
        cat test3_expand.err
    else
        if diff -q test3_input.txt test3_output.txt > /dev/null; then
            echo "✓ Test 3 PASSED"
        else
            echo "✗ Test 3 FAILED"
            echo "Original: $(cat test3_input.txt)"
            echo "Output: $(cat test3_output.txt)"
        fi
    fi
fi
