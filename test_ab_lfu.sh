#!/bin/bash

echo "========================================="
echo "Testing LFU with 'ab' * 50"
echo "minW=3, maxW=4"
echo "========================================="

# Create alphabet file with just 'a' and 'b'
echo "Creating alphabet file..."
echo -e "a\nb" > test_alphabet.txt

# Create test input: "ab" repeated 50 times
echo "Creating test input: 'ab' * 50..."
python3 -c "print('ab' * 50, end='')" > test_input.txt

echo "Test input length: $(wc -c < test_input.txt) bytes"
echo "Test input content: $(cat test_input.txt)"
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

# Compress with LFU policy
echo "========================================="
echo "Running COMPRESSION with LFU policy..."
echo "========================================="
java LZWTool --mode compress --minW 3 --maxW 4 --policy lfu --alphabet test_alphabet.txt < test_input.txt > test_compressed.lzw 2> compress_debug.log
if [ $? -ne 0 ]; then
    echo "Compression failed!"
    cat compress_debug.log
    exit 1
fi
echo "Compression complete!"
echo "Compressed size: $(wc -c < test_compressed.lzw) bytes"
echo ""

# Show compression debug output
echo "========================================="
echo "COMPRESSION DEBUG OUTPUT:"
echo "========================================="
cat compress_debug.log
echo ""

# Expand
echo "========================================="
echo "Running EXPANSION..."
echo "========================================="
java LZWTool --mode expand < test_compressed.lzw > test_output.txt 2> expand_debug.log
if [ $? -ne 0 ]; then
    echo "Expansion failed!"
    cat expand_debug.log
    exit 1
fi
echo "Expansion complete!"
echo ""

# Show expansion debug output
echo "========================================="
echo "EXPANSION DEBUG OUTPUT:"
echo "========================================="
cat expand_debug.log
echo ""

# Compare results
echo "========================================="
echo "VERIFICATION:"
echo "========================================="
echo "Original input:  $(cat test_input.txt)"
echo "Decompressed:    $(cat test_output.txt)"
echo ""

if diff -q test_input.txt test_output.txt > /dev/null; then
    echo "✓ SUCCESS! Input and output match perfectly!"
    exit 0
else
    echo "✗ FAILURE! Input and output do NOT match!"
    echo ""
    echo "Differences:"
    diff test_input.txt test_output.txt
    exit 1
fi
