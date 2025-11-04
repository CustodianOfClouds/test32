#!/bin/bash

# Test LZWTool with real files using ASCII alphabet

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}=====================================${NC}"
echo -e "${BLUE}Testing LZWTool with Real Files${NC}"
echo -e "${BLUE}=====================================${NC}\n"

# Compile
echo -e "${BLUE}Compiling...${NC}"
javac LZWTool.java BinaryStdIn.java BinaryStdOut.java TSTmod.java 2>&1
if [ $? -ne 0 ]; then
    echo -e "${RED}Compilation failed!${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Compilation successful${NC}\n"

# Test files
FILES=(
    "TestFiles/code.txt:text file"
    "TestFiles/medium.txt:medium text"
    "TestFiles/all.tar:tar archive"
    "TestFiles/wacky.bmp:bitmap image"
    "TestFiles/gone_fishing.bmp:small bitmap"
    "TestFiles/frosty.jpg:JPEG (should fail with ASCII)"
)

POLICIES=("freeze" "reset" "lru" "lfu")

echo -e "${BLUE}Testing with ASCII alphabet (alphabets/ascii.txt)${NC}"
echo -e "${BLUE}=====================================${NC}\n"

for file_desc in "${FILES[@]}"; do
    IFS=':' read -r filepath description <<< "$file_desc"

    if [ ! -f "$filepath" ]; then
        echo -e "${YELLOW}⊘ Skipping $description - file not found${NC}"
        continue
    fi

    filename=$(basename "$filepath")
    filesize=$(stat -f%z "$filepath" 2>/dev/null || stat -c%s "$filepath" 2>/dev/null)

    echo -e "${BLUE}Testing: $description ($filename) - ${filesize} bytes${NC}"

    for policy in "${POLICIES[@]}"; do
        # Compress
        cat "$filepath" | java LZWTool --mode compress --alphabet alphabets/ascii.txt \
            --minW 9 --maxW 16 --policy "$policy" > /tmp/test.lzw 2>/tmp/error.txt

        if [ $? -ne 0 ]; then
            error_msg=$(cat /tmp/error.txt)
            if [[ "$error_msg" == *"not in the alphabet"* ]]; then
                echo -e "  ${YELLOW}$policy: Correctly rejected - contains non-ASCII bytes${NC}"
            else
                echo -e "  ${RED}$policy: Compression failed - $error_msg${NC}"
            fi
            continue
        fi

        compressed_size=$(stat -f%z /tmp/test.lzw 2>/dev/null || stat -c%s /tmp/test.lzw 2>/dev/null)

        # Expand
        cat /tmp/test.lzw | java LZWTool --mode expand > /tmp/test.out 2>/tmp/error.txt

        if [ $? -ne 0 ]; then
            echo -e "  ${RED}$policy: Decompression failed${NC}"
            continue
        fi

        # Compare
        if cmp -s "$filepath" /tmp/test.out; then
            ratio=$(echo "scale=4; $compressed_size / $filesize" | bc)
            echo -e "  ${GREEN}✓ $policy: ${compressed_size} bytes (ratio: $ratio)${NC}"
        else
            echo -e "  ${RED}✗ $policy: Output doesn't match input!${NC}"
        fi
    done
    echo ""
done

# Cleanup
rm -f /tmp/test.lzw /tmp/test.out /tmp/error.txt

echo -e "${GREEN}Done!${NC}"
