#!/bin/bash

# Test to compare FREEZE vs LFU policies on the edge case
# This will show if LFU is actually making different eviction decisions

TEST_INPUT="bbbbbababbaaabbbabaabaaabbbabbbbabbbababaabababbbbbaababbabababaaabbaabb"
MIN_W=3
MAX_W=4

echo "========================================"
echo "COMPARING FREEZE vs LFU POLICIES"
echo "========================================"
echo "Test input: $TEST_INPUT"
echo "Input length: ${#TEST_INPUT} bytes"
echo "minW=$MIN_W, maxW=$MAX_W (max codebook size = 2^$MAX_W = 16 entries)"
echo ""

# Create alphabet file
echo -e "a\nb" > test_alphabet.txt

# Create test input file
echo -n "$TEST_INPUT" > test_edge_case.txt

echo "========================================"
echo "TEST 1: FREEZE POLICY"
echo "========================================"
java LZWTool --mode compress --minW $MIN_W --maxW $MAX_W --policy freeze --alphabet test_alphabet.txt < test_edge_case.txt > test_freeze.lzw 2> freeze_compress_debug.log
FREEZE_SIZE=$(wc -c < test_freeze.lzw)
echo "Compressed size (freeze): $FREEZE_SIZE bytes"
echo ""

# Decompress freeze
java LZWTool --mode expand < test_freeze.lzw > test_freeze_output.txt 2> freeze_expand_debug.log

# Verify freeze
if diff -q test_edge_case.txt test_freeze_output.txt > /dev/null; then
    echo "✓ FREEZE decompression: SUCCESS"
else
    echo "✗ FREEZE decompression: FAILED"
fi
echo ""

echo "========================================"
echo "TEST 2: LFU POLICY"
echo "========================================"
java LZWTool --mode compress --minW $MIN_W --maxW $MAX_W --policy lfu --alphabet test_alphabet.txt < test_edge_case.txt > test_lfu.lzw 2> lfu_compress_debug.log
LFU_SIZE=$(wc -c < test_lfu.lzw)
echo "Compressed size (lfu): $LFU_SIZE bytes"
echo ""

# Decompress lfu
java LZWTool --mode expand < test_lfu.lzw > test_lfu_output.txt 2> lfu_expand_debug.log

# Verify lfu
if diff -q test_edge_case.txt test_lfu_output.txt > /dev/null; then
    echo "✓ LFU decompression: SUCCESS"
else
    echo "✗ LFU decompression: FAILED"
fi
echo ""

echo "========================================"
echo "COMPRESSION COMPARISON"
echo "========================================"
echo "Original size:    ${#TEST_INPUT} bytes"
echo "Freeze size:      $FREEZE_SIZE bytes (ratio: $(echo "scale=2; $FREEZE_SIZE * 100 / ${#TEST_INPUT}" | bc)%)"
echo "LFU size:         $LFU_SIZE bytes (ratio: $(echo "scale=2; $LFU_SIZE * 100 / ${#TEST_INPUT}" | bc)%)"
if [ $LFU_SIZE -lt $FREEZE_SIZE ]; then
    DIFF=$((FREEZE_SIZE - LFU_SIZE))
    echo "LFU is BETTER by $DIFF bytes"
elif [ $LFU_SIZE -gt $FREEZE_SIZE ]; then
    DIFF=$((LFU_SIZE - FREEZE_SIZE))
    echo "FREEZE is BETTER by $DIFF bytes"
else
    echo "Both produce SAME size"
fi
echo ""

echo "========================================"
echo "FREEZE COMPRESSION DEBUG (showing eviction behavior)"
echo "========================================"
echo "Looking for 'full' or 'FREEZE' messages..."
grep -i "full\|freeze\|evict" freeze_compress_debug.log | head -20
echo ""

echo "========================================"
echo "LFU COMPRESSION DEBUG (showing eviction behavior)"
echo "========================================"
echo "Looking for 'LFU' or 'EVICT' messages..."
grep -i "lfu\|evict" lfu_compress_debug.log | head -30
echo ""

echo "========================================"
echo "DETAILED LFU STATE ANALYSIS"
echo "========================================"
echo "Showing LFU tracker states and eviction decisions:"
grep -A 15 "LFU: At capacity" lfu_compress_debug.log | head -50
echo ""

echo "========================================"
echo "CODEBOOK BEHAVIOR COMPARISON"
echo "========================================"
echo "FREEZE: Count of 'codebook full' instances:"
grep -c "codebook full" freeze_compress_debug.log || echo "0"
echo ""
echo "LFU: Count of evictions:"
grep -c "EVICTING:" lfu_compress_debug.log || echo "0"
echo ""

# Show a few encoding steps from each to see the difference
echo "========================================"
echo "FREEZE: Last 10 encoding steps"
echo "========================================"
grep "^\\[DEBUG\\] --- Step" freeze_compress_debug.log | tail -10
echo ""

echo "========================================"
echo "LFU: Last 10 encoding steps"
echo "========================================"
grep "^\\[DEBUG\\] --- Step" lfu_compress_debug.log | tail -10
echo ""

echo "========================================"
echo "FINAL CODEBOOK STATE COMPARISON"
echo "========================================"
echo "FREEZE: Final codebook entries (frozen after step X):"
grep -A 5 "FREEZE policy: codebook full" freeze_compress_debug.log | head -20
echo ""

echo "LFU: Final tracker state:"
grep -A 15 "Final LFU state:" lfu_compress_debug.log
