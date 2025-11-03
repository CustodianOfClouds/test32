#!/bin/bash

# Simple debug test
javac LZWTool.java TSTmod.java BinaryStdIn.java BinaryStdOut.java

echo -e "a\nb" > alphabet_ab.txt
python3 -c "print('ab' * 10, end='')" > test_input.txt

echo "Input: $(cat test_input.txt)"
echo ""
echo "=== COMPRESSION ==="
cat test_input.txt | java -Dlzw.debug=true LZWTool --mode compress --minW 3 --maxW 3 --policy lru --alphabet alphabet_ab.txt > test_compressed.lzw 2> compress.log

cat compress.log | head -50

echo ""
echo "=== DECOMPRESSION ==="
cat test_compressed.lzw | java -Dlzw.debug=true LZWTool --mode expand > test_output.txt 2> expand.log

cat expand.log | head -50

echo ""
echo "Output: $(cat test_output.txt)"
echo ""

if diff -q test_input.txt test_output.txt > /dev/null; then
    echo "✓ PASSED"
else
    echo "✗ FAILED"
fi
