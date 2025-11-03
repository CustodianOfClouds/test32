#!/bin/bash

echo "========================================="
echo "Testing LRU with failing case"
echo "minW=3, maxW=4"
echo "========================================="

# Create alphabet file with just 'a' and 'b'
echo "Creating alphabet file..."
echo -e "a\nb" > test_alphabet_fail.txt

# Create test input
TEST_INPUT="bbbbbababbaaabbbabaabaaabbbabbbbabbbababaabababbbbbaababbabababaaabbaabb"
echo "Creating test input..."
echo -n "$TEST_INPUT" > test_input_fail.txt

echo "Test input length: $(wc -c < test_input_fail.txt) bytes"
echo "Test input content: $TEST_INPUT"
echo ""

# Compile Java files
echo "Compiling Java files..."
javac LZWTool.java BinaryStdIn.java BinaryStdOut.java TSTmod.java
if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi
echo "Compilation successful!"
echo ""

# Compress with LRU policy
echo "========================================="
echo "Running COMPRESSION with LRU policy..."
echo "========================================="
java LZWTool --mode compress --minW 3 --maxW 4 --policy lru --alphabet test_alphabet_fail.txt < test_input_fail.txt > test_compressed_fail.lzw 2> compress_debug_fail.log
if [ $? -ne 0 ]; then
    echo "Compression failed!"
    cat compress_debug_fail.log
    exit 1
fi
echo "Compression complete!"
echo "Compressed size: $(wc -c < test_compressed_fail.lzw) bytes"
echo ""

# Show last 50 lines of compression debug output
echo "========================================="
echo "COMPRESSION DEBUG OUTPUT (last 100 lines):"
echo "========================================="
tail -100 compress_debug_fail.log
echo ""

# Expand
echo "========================================="
echo "Running EXPANSION..."
echo "========================================="
java LZWTool --mode expand < test_compressed_fail.lzw > test_output_fail.txt 2> expand_debug_fail.log
if [ $? -ne 0 ]; then
    echo "Expansion failed!"
    cat expand_debug_fail.log
    exit 1
fi
echo "Expansion complete!"
echo ""

# Show last 50 lines of expansion debug output
echo "========================================="
echo "EXPANSION DEBUG OUTPUT (last 100 lines):"
echo "========================================="
tail -100 expand_debug_fail.log
echo ""

# Compare results
echo "========================================="
echo "VERIFICATION:"
echo "========================================="
echo "Original input:  $TEST_INPUT"
echo "Decompressed:    $(cat test_output_fail.txt)"
echo ""
echo "Original length: $(echo -n "$TEST_INPUT" | wc -c) bytes"
echo "Decompressed length: $(wc -c < test_output_fail.txt) bytes"
echo ""

if diff -q test_input_fail.txt test_output_fail.txt > /dev/null; then
    echo "✓ SUCCESS! Input and output match perfectly!"
    exit 0
else
    echo "✗ FAILURE! Input and output do NOT match!"
    echo ""
    echo "Detailed comparison:"
    diff -y test_input_fail.txt test_output_fail.txt | head -20
    echo ""
    echo "Character-by-character comparison:"
    od -An -tc test_input_fail.txt > /tmp/orig.txt
    od -An -tc test_output_fail.txt > /tmp/decomp.txt
    diff /tmp/orig.txt /tmp/decomp.txt | head -30
    exit 1
fi
