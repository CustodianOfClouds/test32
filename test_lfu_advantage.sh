#!/bin/bash

# Test designed to show LFU advantage over FREEZE
# Strategy: Create input where early patterns become obsolete
# and new patterns would be more beneficial

echo "========================================"
echo "TEST: LFU ADVANTAGE SCENARIO"
echo "========================================"
echo "Strategy: Use patterns that change over time"
echo "  - Phase 1: 'aaa' patterns  (should get low frequency)"
echo "  - Phase 2: 'bbb' patterns  (should dominate frequency)"
echo "  - LFU should evict 'aaa' patterns to make room for 'bbb'"
echo ""

# Create a pattern that shifts from 'a' heavy to 'b' heavy
# First part uses 'a' patterns, second part uses 'b' patterns heavily
TEST_INPUT="aaaaabaaabaaabaaabaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

MIN_W=3
MAX_W=4

echo "Input: ${TEST_INPUT:0:50}... (truncated)"
echo "Input length: ${#TEST_INPUT} bytes"
echo "Pattern: 'aaaa' repeated 5x, then 'bbbb' repeated 25x"
echo ""

# Create alphabet
echo -e "a\nb" > test_alphabet.txt
echo -n "$TEST_INPUT" > test_shifting_pattern.txt

echo "========================================"
echo "FREEZE POLICY"
echo "========================================"
java LZWTool --mode compress --minW $MIN_W --maxW $MAX_W --policy freeze --alphabet test_alphabet.txt < test_shifting_pattern.txt > test_freeze2.lzw 2> freeze2_debug.log
FREEZE_SIZE=$(wc -c < test_freeze2.lzw)
echo "Compressed size: $FREEZE_SIZE bytes"

java LZWTool --mode expand < test_freeze2.lzw > test_freeze2_output.txt 2> /dev/null
if diff -q test_shifting_pattern.txt test_freeze2_output.txt > /dev/null; then
    echo "✓ Decompression: SUCCESS"
else
    echo "✗ Decompression: FAILED"
fi
echo ""

echo "========================================"
echo "LFU POLICY"
echo "========================================"
java LZWTool --mode compress --minW $MIN_W --maxW $MAX_W --policy lfu --alphabet test_alphabet.txt < test_shifting_pattern.txt > test_lfu2.lzw 2> lfu2_debug.log
LFU_SIZE=$(wc -c < test_lfu2.lzw)
echo "Compressed size: $LFU_SIZE bytes"

java LZWTool --mode expand < test_lfu2.lzw > test_lfu2_output.txt 2> /dev/null
if diff -q test_shifting_pattern.txt test_lfu2_output.txt > /dev/null; then
    echo "✓ Decompression: SUCCESS"
else
    echo "✗ Decompression: FAILED"
fi
echo ""

echo "========================================"
echo "RESULTS"
echo "========================================"
echo "Original:  ${#TEST_INPUT} bytes"
echo "Freeze:    $FREEZE_SIZE bytes"
echo "LFU:       $LFU_SIZE bytes"
if [ $LFU_SIZE -lt $FREEZE_SIZE ]; then
    DIFF=$((FREEZE_SIZE - LFU_SIZE))
    echo ""
    echo "🎉 LFU IS BETTER by $DIFF bytes!"
elif [ $LFU_SIZE -gt $FREEZE_SIZE ]; then
    DIFF=$((LFU_SIZE - FREEZE_SIZE))
    echo ""
    echo "FREEZE is better by $DIFF bytes"
else
    echo ""
    echo "Same compression ratio"
fi
echo ""

echo "========================================"
echo "FREEZE: What got added to codebook?"
echo "========================================"
grep "ADDING to codebook:" freeze2_debug.log | head -15
echo ""

echo "========================================"
echo "LFU: Eviction activity"
echo "========================================"
echo "Number of evictions: $(grep -c "EVICTING:" lfu2_debug.log || echo 0)"
echo ""
echo "What got evicted:"
grep "EVICTING:" lfu2_debug.log | head -20
echo ""
echo "LFU final state (showing what survived):"
grep -A 12 "Final LFU state:" lfu2_debug.log
echo ""

echo "========================================"
echo "COMPARISON: Early vs Late codebook usage"
echo "========================================"
echo "FREEZE: First 20 outputs:"
grep "OUTPUT: code=" freeze2_debug.log | head -20 | awk '{print $3, $5}'
echo ""
echo "LFU: First 20 outputs:"
grep "OUTPUT: code=" lfu2_debug.log | head -20 | awk '{print $3, $5}'
