# LZWTool Complete Optimization Summary

## ✅ Verification Results

### All 4 Policies Produce Different Results on Very Large Tests (10,000 chars)

| Alphabet | FREEZE | RESET | LRU | LFU | All Different? |
|----------|---------|-------|-----|-----|----------------|
| **ab** (2 chars) | 0.1951 | 0.2678 (+37%) | 0.2628 (+35%) | 0.2033 (+4%) | ✅ YES |
| **abracadabra** (5 chars) | 0.3711 | 0.4456 (+20%) | 0.3767 (+2%) | 0.3729 (+0.5%) | ✅ YES |
| **tobeornot** (6 chars) | 0.4487 | 0.4889 (+9%) | 0.4620 (+3%) | 0.4565 (+2%) | ✅ YES |

**All 232+ comprehensive tests PASSED ✓**

---

## 🚀 Complete List of Optimizations

### **1. Compression Loop (compress function)**

#### A. StringBuilder Pooling - Lines 601, 614-616
**Before:**
```java
while (!BinaryStdIn.isEmpty()) {
    c = BinaryStdIn.readChar();
    StringBuilder next = new StringBuilder(current).append(c);  // ❌ N allocations
}
```

**After:**
```java
StringBuilder nextBuilder = new StringBuilder(256);  // ✅ Reusable
while (!BinaryStdIn.isEmpty()) {
    c = BinaryStdIn.readChar();
    nextBuilder.setLength(0);
    nextBuilder.append(current).append(c);  // ✅ 1 allocation total
}
```

**Savings:** ~1 million StringBuilder allocations eliminated per 1MB file

---

#### B. String Caching - Lines 636-639, 750-753
**Before:**
```java
if (lruPolicy) {
    lruTracker.use(current.toString());  // ❌ Allocation 1
}
if (lfuPolicy) {
    lfuTracker.use(current.toString());  // ❌ Allocation 2
}
```

**After:**
```java
String currentStr = null;
if (lruPolicy || lfuPolicy) {
    currentStr = current.toString();  // ✅ Single allocation
}
if (lruPolicy) lruTracker.use(currentStr);
if (lfuPolicy) lfuTracker.use(currentStr);
```

**Savings:** Eliminates duplicate toString() calls when both policies need the string

---

#### C. Boolean Array Alphabet Validation - Lines 528-531, 590-592, 609-612
**Before:**
```java
HashSet<Character> alphabetSet = new HashSet<>();
for (Character symbol : alphabet) {
    alphabetSet.add(symbol);  // Boxing
}
if (!alphabetSet.contains(c)) { ... }  // Hash + equals + boxing
```

**After:**
```java
boolean[] validChar = new boolean[256];
for (Character symbol : alphabet) {
    validChar[symbol] = true;  // Direct array access
}
if (!validChar[c]) { ... }  // Single array access, no boxing
```

**Savings:** ~2N operations eliminated (2 validations per char) × (hash + boxing overhead)

---

#### D. Cached Bit-Shift Operations - Lines 604, 652-654, 728, 766
**Before:**
```java
while (...) {
    if (nextCode >= (1 << W) && W < maxW) { ... }  // ❌ Shift every loop iteration
}
```

**After:**
```java
int widthThreshold = 1 << W;  // ✅ Calculate once
while (...) {
    if (nextCode >= widthThreshold && W < maxW) {
        W++;
        widthThreshold = 1 << W;  // Update only when W changes
    }
}
```

**Savings:** ~N bit-shift operations eliminated for N-character input

---

### **2. Decompression Loop (expand function)**

#### A. Cached Bit-Shift Operations - Lines 867, 871-873, 895
Same optimization as compression loop.

---

### **3. Alphabet Loading (loadAlphabet function)** - Lines 456-515

#### A. Boolean Array for Duplicate Detection - Lines 462
**Before:**
```java
Set<Character> seen = new LinkedHashSet<>();  // HashSet with boxing
```

**After:**
```java
boolean[] seen = new boolean[256];  // Direct array access, no boxing
```

**Savings:** No boxing overhead, O(1) lookup without hashing

---

#### B. Pre-allocated Collections - Lines 459, 472
**Before:**
```java
List<Character> alphabet = new ArrayList<>();  // Default capacity 10
StringBuilder lineBuffer = new StringBuilder();  // Default capacity 16
```

**After:**
```java
List<Character> alphabet = new ArrayList<>(256);  // Max extended ASCII
StringBuilder lineBuffer = new StringBuilder(16);  // Typical line length
```

**Savings:** Avoids ArrayList/StringBuilder resizing (internal array copies)

---

#### C. Primitive char Usage - Lines 483, 504
**Before:**
```java
Character symbol = lineBuffer.charAt(0);  // ❌ Boxing
if (symbol != null && !seen.contains(symbol)) { ... }  // Boxing on contains
```

**After:**
```java
char symbol = lineBuffer.charAt(0);  // ✅ Primitive
if (!seen[symbol]) { ... }  // Direct array access
```

**Savings:** Eliminates Character object creation for each unique symbol

---

### **4. Header Writing (writeHeader function)** - Lines 1041-1075

#### A. Cached ArrayList Size - Lines 1067-1068
**Before:**
```java
BinaryStdOut.write(alphabet.size(), 16);  // Method call
for (Character symbol : alphabet) {
    // Could call .size() implicitly
}
```

**After:**
```java
int alphabetSize = alphabet.size();  // ✅ Cache the value
BinaryStdOut.write(alphabetSize, 16);
```

**Savings:** Avoids potential repeated method calls

---

#### B. Removed Unnecessary Null Check - Line 1072
**Before:**
```java
BinaryStdOut.write(symbol != null ? symbol : 0, 8);  // ❌ Unnecessary ternary
```

**After:**
```java
BinaryStdOut.write(symbol, 8);  // ✅ Alphabet never contains null by design
```

**Savings:** Removes conditional check on every symbol write

---

### **5. Header Reading (readHeader function)** - Lines 1076-1094

#### A. Pre-allocated ArrayList - Line 1088
**Before:**
```java
header.alphabet = new ArrayList<>();  // Default capacity 10
for (int i = 0; i < alphabetSize; i++) {
    header.alphabet.add(...);  // May resize multiple times
}
```

**After:**
```java
header.alphabet = new ArrayList<>(alphabetSize);  // ✅ Exact size
for (int i = 0; i < alphabetSize; i++) {
    header.alphabet.add(...);  // No resizing needed
}
```

**Savings:** Eliminates 1-2 internal array copies during ArrayList growth

---

## 📊 Optimization Impact Summary

| Category | Optimization | Impact | Lines |
|----------|-------------|--------|-------|
| **Memory** | StringBuilder pooling | HIGH - ~1M allocations/MB eliminated | 601, 614-616 |
| **Memory** | String caching | MEDIUM - Eliminates duplicate toString() | 636-639 |
| **Memory** | Boolean array (compress) | HIGH - No boxing, faster validation | 528-531 |
| **Memory** | Boolean array (loadAlphabet) | MEDIUM - No boxing during loading | 462 |
| **Memory** | Pre-allocated collections | LOW - Avoids resizing | 459, 472, 1088 |
| **CPU** | Boolean array validation | HIGH - Array access vs hash+box | 590-592, 609-612 |
| **CPU** | Cached bit-shift | MEDIUM - ~N shifts eliminated | 604, 652-654 |
| **CPU** | Primitive char usage | MEDIUM - No Character boxing | 483, 504 |
| **CPU** | Removed null check | LOW - Removes conditional | 1072 |
| **Cleanliness** | Cached size | LOW - Avoids method calls | 1067 |

---

## 🧪 Testing Coverage

### Comprehensive Test Suite: **ALL PASSED (232+ tests)**

**Test Categories:**
1. ✅ Alphabet validation (rejects invalid input correctly)
2. ✅ Edge cases (8/8 passed - bad args, missing files, invalid chars, etc.)
3. ✅ Multiple alphabets (ab, abracadabra, tobeornot)
4. ✅ All policies (freeze, reset, lru, lfu)
5. ✅ Various sequence types (empty, single, alternating, random, edge cases)
6. ✅ Different sizes (short=20, medium=100, long=500, very_long=10000)
7. ✅ Real files (TestFiles/code.txt, medium.txt with ASCII alphabet)

**Policy Differentiation:**
- ✅ FREEZE vs RESET: Different
- ✅ FREEZE vs LRU: Different
- ✅ FREEZE vs LFU: Different
- ✅ RESET vs LRU: Different
- ✅ RESET vs LFU: Different
- ✅ LRU vs LFU: Different

---

## 🎯 Key Principles Applied

1. **Avoid boxing**: Use primitive `char` instead of `Character` where possible
2. **Reuse objects**: Pool StringBuilders instead of creating new ones
3. **Pre-allocate**: Set initial capacity when size is known or estimatable
4. **Cache results**: Store computed values (toString(), bit-shifts) instead of recomputing
5. **Use arrays for simple lookups**: Boolean arrays faster than HashSet for small domains
6. **Remove unnecessary checks**: Trust data invariants (no null in alphabet)

---

## ✅ Final Verification

**Alphabet Validation Test:**
```
$ java LZWTool --mode compress --alphabet alphabets/ab.txt < TestFiles/frosty.jpg
Error: Input contains byte value 255 which is not in the alphabet
```
✅ Optimized boolean array validation works perfectly!

**All Policies Differ Test:**
```
ab alphabet (10K random): FREEZE=0.1951, RESET=0.2678, LRU=0.2628, LFU=0.2033
```
✅ All 4 policies produce different compression ratios!

**Full Test Suite:**
```
232+ compression/decompression cycles - ALL PASSED
```
✅ All optimizations preserve correctness!

---

## 🚀 Conclusion

**The LZWTool is now fully optimized:**

✅ **compress() function**: StringBuilder pooling, string caching, boolean array validation, cached bit-shifts
✅ **expand() function**: Cached bit-shift operations
✅ **loadAlphabet() function**: Boolean array tracking, pre-allocation, primitive char usage
✅ **writeHeader() function**: Cached size, removed null check
✅ **readHeader() function**: Pre-allocated ArrayList

**All optimizations verified working with 100% test success rate!**

**Memory savings:** ~1 million allocations eliminated per MB of input
**CPU savings:** ~2N hash operations + ~N bit-shifts eliminated
**Correctness:** All 232+ tests pass, all policies work correctly
