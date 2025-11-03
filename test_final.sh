#!/bin/bash

# Final comprehensive test with your specific test cases

echo "Compiling..."
javac LZWTool.java TSTmod.java BinaryStdIn.java BinaryStdOut.java

# Test case 1: abracadabra alphabet with random medium string
echo "=== Test Case 1: abracadabra alphabet with random 100-char string ==="
echo -e "A\nB\nR\nC\nD" > alphabet_abracadabra.txt

python3 -c "
import random
random.seed(42)
chars = ['A', 'B', 'R', 'C', 'D']
s = ''.join(random.choice(chars) for _ in range(100))
print(s, end='')
" > test1_input.txt

echo "Input: $(cat test1_input.txt)"
echo ""

for minW in 3 4 5; do
    for maxW in 4 5 6; do
        if [ $minW -le $maxW ]; then
            echo "Testing minW=$minW, maxW=$maxW..."
            cat test1_input.txt | java LZWTool --mode compress --minW $minW --maxW $maxW --policy lru --alphabet alphabet_abracadabra.txt > test1_compressed.lzw 2>&1
            cat test1_compressed.lzw | java LZWTool --mode expand > test1_output.txt 2>&1

            if diff -q test1_input.txt test1_output.txt > /dev/null; then
                echo "  ✓ PASSED"
            else
                echo "  ✗ FAILED"
                exit 1
            fi
        fi
    done
done

echo ""

# Test case 2: ab alphabet with alternating string
echo "=== Test Case 2: ab alphabet with alternating 'ab' * 50 ==="
echo -e "a\nb" > alphabet_ab.txt

python3 -c "print('ab' * 50, end='')" > test2_input.txt

echo "Input: $(cat test2_input.txt)"
echo ""

for minW in 3 4; do
    for maxW in 3 4 5; do
        if [ $minW -le $maxW ]; then
            echo "Testing minW=$minW, maxW=$maxW..."
            cat test2_input.txt | java LZWTool --mode compress --minW $minW --maxW $maxW --policy lru --alphabet alphabet_ab.txt > test2_compressed.lzw 2>&1
            cat test2_compressed.lzw | java LZWTool --mode expand > test2_output.txt 2>&1

            if diff -q test2_input.txt test2_output.txt > /dev/null; then
                echo "  ✓ PASSED"
            else
                echo "  ✗ FAILED"
                exit 1
            fi
        fi
    done
done

echo ""
echo "========================================="
echo "ALL TESTS PASSED!"
echo "========================================="
