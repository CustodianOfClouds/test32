# LZWTool Optimization Analysis

## ✅ Results: All 4 Policies Differ on Very Large Tests

```
Alphabet: ab (10,000 chars)
  FREEZE:  1951 bytes (0.1951) - baseline
  RESET:   2678 bytes (0.2678) - +37.26%
  LRU:     2628 bytes (0.2628) - +34.70%
  LFU:     2033 bytes (0.2033) - +4.20%

Alphabet: abracadabra (10,000 chars)
  FREEZE:  3711 bytes (0.3711) - baseline
  RESET:   4456 bytes (0.4456) - +20.08%
  LRU:     3767 bytes (0.3767) - +1.51%
  LFU:     3729 bytes (0.3729) - +0.49%

Alphabet: tobeornot (10,000 chars)
  FREEZE:  4487 bytes (0.4487) - baseline
  RESET:   4889 bytes (0.4889) - +8.96%
  LRU:     4620 bytes (0.4620) - +2.96%
  LFU:     4565 bytes (0.4565) - +1.74%
```

---

## 🔍 Optimization Opportunities in Non-Compress/Expand Functions

### 1. **loadAlphabet() - Lines 456-511**

**Current Issues:**

❌ **Boxing overhead (lines 480, 500)**
```java
Character symbol = (lineBuffer.length() == 0) ? null : lineBuffer.charAt(0);
if (symbol != null && !seen.contains(symbol)) {
    seen.add(symbol);
    alphabet.add(symbol);
}
```
- Creates `Character` object unnecessarily
- Boxing happens on `seen.add()` and `alphabet.add()`

❌ **No initial capacity for ArrayList/StringBuilder**
```java
List<Character> alphabet = new ArrayList<>();  // No capacity
StringBuilder lineBuffer = new StringBuilder(); // No capacity
```

**Optimizations:**

✅ **Use primitive char and avoid boxing**
```java
char symbol = lineBuffer.charAt(0);
if (!seen.contains(symbol)) {
    seen.add(symbol);
    alphabet.add(symbol);
}
```

✅ **Pre-allocate with estimated capacity**
```java
List<Character> alphabet = new ArrayList<>(256);  // Max extended ASCII
StringBuilder lineBuffer = new StringBuilder(16); // Typical line length
```

✅ **Use boolean array for seen tracking (like alphabet validation)**
```java
boolean[] seen = new boolean[256];
// Then no need for HashSet lookup
```

**Estimated savings:** ~256 Character object allocations + faster lookups

---

### 2. **writeHeader() - Lines 1037-1070**

**Current Issues:**

❌ **Unnecessary null check (line 1068)**
```java
BinaryStdOut.write(symbol != null ? symbol : 0, 8);
```
- Alphabet should never contain null by design

❌ **Switch statement for policy (lines 1043-1059)**
- Not really an issue since it's only called once per compression
- But could use a HashMap for cleaner code

**Optimizations:**

✅ **Remove unnecessary null check**
```java
BinaryStdOut.write(symbol, 8);
```

✅ **Cache alphabet size to avoid repeated .size() calls**
```java
int alphabetSize = alphabet.size();
BinaryStdOut.write(alphabetSize, 16);
```

**Estimated savings:** Minimal (called once per file), but cleaner

---

### 3. **readHeader() - Lines 1072-1089**

**Current Issues:**

❌ **ArrayList created without capacity (line 1083)**
```java
header.alphabet = new ArrayList<>();
for (int i = 0; i < alphabetSize; i++) {
    header.alphabet.add(BinaryStdIn.readChar(8));
}
```
- Known size `alphabetSize` but not pre-allocated

**Optimizations:**

✅ **Pre-allocate with known size**
```java
header.alphabet = new ArrayList<>(alphabetSize);
```

**Estimated savings:** Avoids ArrayList resizing (typically 1-2 internal array copies)

---

### 4. **parseArguments() - Lines 404-427**

**Current Issues:**

❌ **No bounds checking on ++i**
```java
case "--mode":
    mode = args[++i];  // Could throw ArrayIndexOutOfBoundsException
    break;
```

❌ **Multiple System.exit() calls**
- Not terrible, but could be cleaner with exceptions

**Optimizations:**

✅ **Add bounds checking**
```java
case "--mode":
    if (i + 1 >= args.length) {
        System.err.println("Error: --mode requires a value");
        System.exit(1);
    }
    mode = args[++i];
    break;
```

**Estimated savings:** Better error messages, prevents crashes

---

### 5. **validateCompressionArgs() - Lines 397-402**

**Current State:**
```java
if (alphabetPath == null || minW > maxW) {
    System.err.println("Error: Invalid arguments for compression");
    System.exit(1);
}
```

**Optimizations:**

✅ **More specific error messages**
```java
if (alphabetPath == null) {
    System.err.println("Error: --alphabet is required for compression");
    System.exit(1);
}
if (minW > maxW) {
    System.err.println("Error: minW (" + minW + ") cannot be greater than maxW (" + maxW + ")");
    System.exit(1);
}
```

**Estimated savings:** Better debugging experience

---

## 📊 Priority Ranking

| Function | Impact | Effort | Priority |
|----------|--------|--------|----------|
| `loadAlphabet()` - Use primitive char | **HIGH** | Low | **P0** |
| `loadAlphabet()` - Pre-allocate ArrayList | **MEDIUM** | Low | **P1** |
| `readHeader()` - Pre-allocate ArrayList | **LOW** | Low | **P2** |
| `writeHeader()` - Remove null check | **LOW** | Low | **P2** |
| `parseArguments()` - Bounds checking | **LOW** | Medium | **P3** |

---

## 🎯 Recommended Optimizations

### **loadAlphabet() - MOST IMPACTFUL**

```java
private static List<Character> loadAlphabet(String path) {
    // Pre-allocate with max extended ASCII size
    List<Character> alphabet = new ArrayList<>(256);

    // Use boolean array instead of HashSet for O(1) lookup without boxing
    boolean[] seen = new boolean[256];

    // Always include CR and LF
    alphabet.add('\r'); seen['\r'] = true;
    alphabet.add('\n'); seen['\n'] = true;

    try (InputStreamReader reader = new InputStreamReader(new FileInputStream(path), "UTF-8")) {
        // Pre-allocate with typical line length
        StringBuilder lineBuffer = new StringBuilder(16);
        int c;

        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                // Use primitive char to avoid boxing
                if (lineBuffer.length() > 0) {
                    char symbol = lineBuffer.charAt(0);
                    if (!seen[symbol]) {
                        seen[symbol] = true;
                        alphabet.add(symbol);
                    }
                }
                lineBuffer.setLength(0);
            } else {
                lineBuffer.append((char) c);
            }
        }

        // Handle last line
        if (lineBuffer.length() > 0) {
            char symbol = lineBuffer.charAt(0);
            if (!seen[symbol]) {
                seen[symbol] = true;
                alphabet.add(symbol);
            }
        }
    } catch (IOException e) {
        return null;
    }

    return alphabet;
}
```

### **readHeader() - SIMPLE WIN**

```java
private static Header readHeader() {
    Header header = new Header();

    header.minW = BinaryStdIn.readInt(8);
    header.maxW = BinaryStdIn.readInt(8);
    header.policy = BinaryStdIn.readInt(8);

    int alphabetSize = BinaryStdIn.readInt(16);
    header.alphabetSize = alphabetSize;

    // Pre-allocate with known size
    header.alphabet = new ArrayList<>(alphabetSize);
    for (int i = 0; i < alphabetSize; i++) {
        header.alphabet.add(BinaryStdIn.readChar(8));
    }

    return header;
}
```

### **writeHeader() - MINOR CLEANUP**

```java
private static void writeHeader(int minW, int maxW, String policy, List<Character> alphabet) {
    BinaryStdOut.write(minW, 8);
    BinaryStdOut.write(maxW, 8);

    int policyCode;
    switch (policy) {
        case "freeze": policyCode = 0; break;
        case "reset":  policyCode = 1; break;
        case "lru":    policyCode = 2; break;
        case "lfu":    policyCode = 3; break;
        default:       policyCode = 0; break;
    }
    BinaryStdOut.write(policyCode, 8);

    // Cache size to avoid multiple method calls
    int alphabetSize = alphabet.size();
    BinaryStdOut.write(alphabetSize, 16);

    // Remove unnecessary null check - alphabet should never contain null
    for (Character symbol : alphabet) {
        BinaryStdOut.write(symbol, 8);
    }
}
```

---

## 💡 Summary

**Current Status:**
- ✅ compress() and expand() are well-optimized
- ✅ All 4 policies work correctly and produce different results
- ⚠️ loadAlphabet() has boxing overhead and no pre-allocation
- ⚠️ readHeader() doesn't pre-allocate ArrayList

**Recommended Changes:**
1. **loadAlphabet()**: Use primitive char + boolean array (like alphabet validation in compress)
2. **readHeader()**: Pre-allocate ArrayList with known size
3. **writeHeader()**: Remove null check, cache alphabet.size()

**Impact:**
- loadAlphabet() called once per compression (minor but good practice)
- readHeader() called once per decompression (minor improvement)
- writeHeader() called once per compression (minimal improvement)

**Overall:** These are micro-optimizations since functions are called once per file, but they follow the same optimization principles used in compress/expand and eliminate unnecessary allocations.
