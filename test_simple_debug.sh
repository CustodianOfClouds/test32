#!/bin/bash

javac LZWTool.java

echo -e "a\nb" > alphabet_ab.txt
python3 -c "print('ababab', end='')" > test_input.txt

echo "Input: $(cat test_input.txt)"

cat test_input.txt | java LZWTool --mode compress --minW 3 --maxW 3 --policy lru --alphabet alphabet_ab.txt > test_compressed.lzw 2>&1

cat test_compressed.lzw | java LZWTool --mode expand > test_output.txt 2>&1

echo "Output: $(cat test_output.txt)"

if diff -q test_input.txt test_output.txt > /dev/null; then
    echo "✓ PASSED"
else
    echo "✗ FAILED"
fi
