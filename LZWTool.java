import java.io.*;
import java.util.*;

public class LZWTool {

    //============================================
    // Main args
    //============================================
    // Compression or expansion mode
    private static String mode;

    // Min codeword width in bits (must be large enough to encode alphabet + EOF)
    private static int minW = 9;

    // Max codeword width in bits (limits codebook size to 2^maxW entries)
    // We assume this can be written in 8 bits = 256 max
    private static int maxW = 16;

    // Eviction policy when codebook is full: freeze, reset, lru, lfu
    private static String policy = "freeze";


    // Path to the alphabet file for initializing the codebook
    private static String alphabetPath;

    private static final boolean DEBUG = true; // Set to false to disable debug output

    // Optimized LRU tracking using timestamps and HashMap for compression
    private static class LRUTracker {
        private final HashMap<String, Integer> map;
        private int timestamp = 0;

        LRUTracker(int capacity) {
            this.map = new HashMap<>(capacity);
            debug("LRUTracker initialized with capacity: " + capacity);
        }

        void use(String key) {
            map.put(key, timestamp++);
            debug("LRUTracker.use('" + escapeString(key) + "') timestamp=" + (timestamp-1) + ", mapSize=" + map.size());
        }

        String findLRU() {
            String lruKey = null;
            int minTimestamp = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                if (entry.getValue() < minTimestamp) {
                    minTimestamp = entry.getValue();
                    lruKey = entry.getKey();
                }
            }
            debug("LRUTracker.findLRU() -> '" + escapeString(lruKey) + "' with timestamp=" + minTimestamp);
            return lruKey;
        }

        void remove(String key) {
            map.remove(key);
            debug("LRUTracker.remove('" + escapeString(key) + "'), mapSize=" + map.size());
        }

        boolean contains(String key) {
            return map.containsKey(key);
        }

        void printState() {
            debug("LRUTracker state (size=" + map.size() + "):");
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Map.Entry.comparingByValue());
            for (Map.Entry<String, Integer> entry : entries) {
                debug("  '" + escapeString(entry.getKey()) + "' -> timestamp=" + entry.getValue());
            }
        }
    }

    // Optimized LRU tracker for decoder (uses Integer keys for codes)
    private static class LRUTrackerDecoder {
        private final HashMap<Integer, Integer> map;
        private int timestamp = 0;

        LRUTrackerDecoder(int capacity) {
            this.map = new HashMap<>(capacity);
            debug("LRUTrackerDecoder initialized with capacity: " + capacity);
        }

        void use(int code) {
            map.put(code, timestamp++);
            debug("LRUTrackerDecoder.use(code=" + code + ") timestamp=" + (timestamp-1) + ", mapSize=" + map.size());
        }

        int findLRU() {
            int lruCode = -1;
            int minTimestamp = Integer.MAX_VALUE;
            for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
                if (entry.getValue() < minTimestamp) {
                    minTimestamp = entry.getValue();
                    lruCode = entry.getKey();
                }
            }
            debug("LRUTrackerDecoder.findLRU() -> code=" + lruCode + " with timestamp=" + minTimestamp);
            return lruCode;
        }

        void remove(int code) {
            map.remove(code);
            debug("LRUTrackerDecoder.remove(code=" + code + "), mapSize=" + map.size());
        }

        void printState() {
            debug("LRUTrackerDecoder state (size=" + map.size() + "):");
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Map.Entry.comparingByValue());
            for (Map.Entry<Integer, Integer> entry : entries) {
                debug("  code=" + entry.getKey() + " -> timestamp=" + entry.getValue());
            }
        }
    }

    // LFU tracking for compression using frequency counts
    private static class LFUTracker {
        // Store frequency count for each key
        private final HashMap<String, Integer> frequency;
        // Store insertion/update timestamp for tie-breaking
        private final HashMap<String, Integer> timestamp;
        private int currentTime = 0;

        LFUTracker(int capacity) {
            this.frequency = new HashMap<>(capacity);
            this.timestamp = new HashMap<>(capacity);
            debug("LFUTracker initialized with capacity: " + capacity);
        }

        void use(String key) {
            // Increment frequency (or set to 1 if new)
            frequency.put(key, frequency.getOrDefault(key, 0) + 1);
            // Update timestamp for tie-breaking
            timestamp.put(key, currentTime++);
            debug("LFUTracker.use('" + escapeString(key) + "') freq=" + frequency.get(key) +
                  ", time=" + (currentTime-1) + ", mapSize=" + frequency.size());
        }

        String findLFU() {
            String lfuKey = null;
            int minFreq = Integer.MAX_VALUE;
            int oldestTime = Integer.MAX_VALUE;

            for (Map.Entry<String, Integer> entry : frequency.entrySet()) {
                String key = entry.getKey();
                int freq = entry.getValue();
                int time = timestamp.get(key);

                // Find least frequently used, break ties by oldest timestamp
                if (freq < minFreq || (freq == minFreq && time < oldestTime)) {
                    minFreq = freq;
                    oldestTime = time;
                    lfuKey = key;
                }
            }
            debug("LFUTracker.findLFU() -> '" + escapeString(lfuKey) + "' with freq=" + minFreq + ", time=" + oldestTime);
            return lfuKey;
        }

        void remove(String key) {
            frequency.remove(key);
            timestamp.remove(key);
            debug("LFUTracker.remove('" + escapeString(key) + "'), mapSize=" + frequency.size());
        }

        boolean contains(String key) {
            return frequency.containsKey(key);
        }

        void printState() {
            debug("LFUTracker state (size=" + frequency.size() + "):");
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(frequency.entrySet());
            entries.sort((e1, e2) -> {
                int freqCmp = e1.getValue().compareTo(e2.getValue());
                if (freqCmp != 0) return freqCmp;
                return timestamp.get(e1.getKey()).compareTo(timestamp.get(e2.getKey()));
            });
            for (Map.Entry<String, Integer> entry : entries) {
                debug("  '" + escapeString(entry.getKey()) + "' -> freq=" + entry.getValue() +
                      ", time=" + timestamp.get(entry.getKey()));
            }
        }
    }

    // LFU tracker for decoder (uses Integer keys for codes)
    private static class LFUTrackerDecoder {
        // Store frequency count for each code
        private final HashMap<Integer, Integer> frequency;
        // Store insertion/update timestamp for tie-breaking
        private final HashMap<Integer, Integer> timestamp;
        private int currentTime = 0;

        LFUTrackerDecoder(int capacity) {
            this.frequency = new HashMap<>(capacity);
            this.timestamp = new HashMap<>(capacity);
            debug("LFUTrackerDecoder initialized with capacity: " + capacity);
        }

        void use(int code) {
            // Increment frequency (or set to 1 if new)
            frequency.put(code, frequency.getOrDefault(code, 0) + 1);
            // Update timestamp for tie-breaking
            timestamp.put(code, currentTime++);
            debug("LFUTrackerDecoder.use(code=" + code + ") freq=" + frequency.get(code) +
                  ", time=" + (currentTime-1) + ", mapSize=" + frequency.size());
        }

        int findLFU() {
            int lfuCode = -1;
            int minFreq = Integer.MAX_VALUE;
            int oldestTime = Integer.MAX_VALUE;

            for (Map.Entry<Integer, Integer> entry : frequency.entrySet()) {
                int code = entry.getKey();
                int freq = entry.getValue();
                int time = timestamp.get(code);

                // Find least frequently used, break ties by oldest timestamp
                if (freq < minFreq || (freq == minFreq && time < oldestTime)) {
                    minFreq = freq;
                    oldestTime = time;
                    lfuCode = code;
                }
            }
            debug("LFUTrackerDecoder.findLFU() -> code=" + lfuCode + " with freq=" + minFreq + ", time=" + oldestTime);
            return lfuCode;
        }

        void remove(int code) {
            frequency.remove(code);
            timestamp.remove(code);
            debug("LFUTrackerDecoder.remove(code=" + code + "), mapSize=" + frequency.size());
        }

        void printState() {
            debug("LFUTrackerDecoder state (size=" + frequency.size() + "):");
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(frequency.entrySet());
            entries.sort((e1, e2) -> {
                int freqCmp = e1.getValue().compareTo(e2.getValue());
                if (freqCmp != 0) return freqCmp;
                return timestamp.get(e1.getKey()).compareTo(timestamp.get(e2.getKey()));
            });
            for (Map.Entry<Integer, Integer> entry : entries) {
                debug("  code=" + entry.getKey() + " -> freq=" + entry.getValue() +
                      ", time=" + timestamp.get(entry.getKey()));
            }
        }
    }

    private static void debug(String msg) {
        if (DEBUG) System.err.println("[DEBUG] " + msg);
    }

    private static String escapeString(String s) {
        if (s == null) return "null";
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // commandline args
    private static void validateCompressionArgs() {
        if (alphabetPath == null || minW > maxW) {
            System.err.println("Error: Invalid arguments for compression");
            System.exit(1);
        }
    }

    private static void parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode":
                    mode = args[++i];
                    break;
                case "--minW":
                    minW = Integer.parseInt(args[++i]);
                    break;
                case "--maxW":
                    maxW = Integer.parseInt(args[++i]);
                    break;
                case "--policy":
                    policy = args[++i];
                    break;
                case "--alphabet":
                    alphabetPath = args[++i];
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
            }
        }
    }

    public static void main(String[] args) { 
        
        parseArguments(args);

        if (mode.equals("compress")) {
            validateCompressionArgs();

            // this is the codebook basically
            List<Character> alphabet = loadAlphabet(alphabetPath);

            if (alphabet == null) {
                System.err.println("Error: Could not load alphabet from " + alphabetPath);
                System.exit(1);
            }

            // we pipe raw binary data in during compress
            compress(minW, maxW, policy, alphabet);

        } else if (mode.equals("expand")) {
            // we pipe raw binary data out during expand
            expand();
        } else {
            System.err.println("Error: --mode must be 'compress' or 'expand'");
            System.exit(1);
        }
    }

    private static List<Character> loadAlphabet(String path) {

        List<Character> alphabet = new ArrayList<>();

        // LinkedHashSet preserves insertion order while ensuring uniqueness
        Set<Character> seen = new LinkedHashSet<>();

        // Always hardcode include CR and LF in the alphabet
        alphabet.add('\r'); seen.add('\r');
        alphabet.add('\n'); seen.add('\n');

        // our extended ascii dictionary is stored in UTF-8 bruh so we gotta read it in UTF-8 before converting into 1 byte chars
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(path), "UTF-8")) {

            StringBuilder lineBuffer = new StringBuilder(); // stores all chars of current line until newline
            int c; // Current character being read

            // Read character by character to handle line endings precisely
            while ((c = reader.read()) != -1) { // as long as its not EOF
                if (c == '\n') {
                    // Found a line ending - process the line (if not, see else)

                    // Extract symbol - empty line = null, otherwise first char
                    // symbol can only be null on LF systems, not CRLF or CR only systems
                    Character symbol = (lineBuffer.length() == 0) ? null : lineBuffer.charAt(0);

                    // Add to alphabet only if we haven't seen it before
                    if (symbol != null && !seen.contains(symbol)) {
                        seen.add(symbol);
                        alphabet.add(symbol);
                    }

                    // Reset buffer for next line
                    lineBuffer.setLength(0);
                } else {
                    // Regular character - add to current line buffer
                    lineBuffer.append((char) c);
                }
            }

            // Handle last line if file doesn't end with newline
            if (lineBuffer.length() > 0) {

                // Extract first character as symbol
                Character symbol = lineBuffer.charAt(0);
                if (!seen.contains(symbol)) {
                    seen.add(symbol);
                    alphabet.add(symbol);
                }
            }
        } catch (IOException e) {
            return null; // Signal error to caller
        }

        return alphabet;
    }

    private static void compress(int minW, int maxW, String policy, List<Character> alphabet) {
        debug("\n=== STARTING COMPRESSION ===");
        debug("Policy: " + policy + ", minW=" + minW + ", maxW=" + maxW);

        writeHeader(minW, maxW, policy, alphabet);

        // compression dictionary stored in TST, String -> Code/Integer
        TSTmod<Integer> dictionary = new TSTmod<>();

        // width tracking
        int W = minW;
        int maxCode = 1 << maxW;
        int alphabetSize = alphabet.size();

        Set<Character> alphabetSet = new HashSet<>(alphabet);
        int nextCode = 0;
        StringBuilder sb = new StringBuilder(1);

        debug("\nInitializing codebook with alphabet:");
        for (Character symbol : alphabet) {
            sb.setLength(0);
            sb.append(symbol);
            dictionary.put(sb, nextCode);
            debug("  '" + escapeString(String.valueOf(symbol)) + "' -> code " + nextCode);
            nextCode++;
        }

        int EOF_CODE = nextCode++;
        debug("EOF_CODE = " + EOF_CODE);

        boolean resetPolicy = policy.equals("reset");
        boolean lruPolicy = policy.equals("lru");
        boolean lfuPolicy = policy.equals("lfu");

        int RESET_CODE = -1;
        if (resetPolicy) {
            RESET_CODE = nextCode++;
            debug("RESET_CODE = " + RESET_CODE);
        }

        int initialNextCode = nextCode;
        debug("initialNextCode = " + initialNextCode);

        // LRU tracking structure
        LRUTracker lruTracker = null;
        if (lruPolicy) {
            lruTracker = new LRUTracker(maxCode);
        }

        // LFU tracking structure
        LFUTracker lfuTracker = null;
        if (lfuPolicy) {
            lfuTracker = new LFUTracker(maxCode);
        }

        // Preload alphabet for reuse in reset mode
        StringBuilder[] alphabetKeys = null;
        if (resetPolicy) {
            alphabetKeys = new StringBuilder[alphabetSize];
            for (int i = 0; i < alphabetSize; i++)
                alphabetKeys[i] = new StringBuilder(String.valueOf(alphabet.get(i)));
        }

        debug("maxCode = " + maxCode);

        if (BinaryStdIn.isEmpty()) {
            debug("Input is empty, closing.");
            BinaryStdOut.close();
            return;
        }

        char c = BinaryStdIn.readChar();
        if (!alphabetSet.contains(c)) {
            System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
            System.exit(1);
        }
        StringBuilder current = new StringBuilder().append(c);
        debug("\nFirst character: '" + escapeString(String.valueOf(c)) + "'");

        int step = 0;
        debug("\n=== ENCODING LOOP ===");
        while (!BinaryStdIn.isEmpty()) {

            c = BinaryStdIn.readChar();
            if (!alphabetSet.contains(c)) {
                System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
                System.exit(1);
            }
            StringBuilder next = new StringBuilder(current).append(c);

            step++;
            debug("\n--- Step " + step + " ---");
            debug("Read char: '" + escapeString(String.valueOf(c)) + "'");
            debug("current = '" + escapeString(current.toString()) + "', next = '" + escapeString(next.toString()) + "'");

            if (dictionary.contains(next)) {
                debug("Codebook CONTAINS '" + escapeString(next.toString()) + "' - extending current");
                current = next;
            } else {
                // Output current pattern
                int outputCode = dictionary.get(current);
                debug("Codebook does NOT contain '" + escapeString(next.toString()) + "'");
                debug("OUTPUT: code=" + outputCode + " for '" + escapeString(current.toString()) + "' (W=" + W + " bits)");
                BinaryStdOut.write(outputCode, W);

                // Update LRU: mark this code as recently used (only if it's already tracked)
                if (lruPolicy) {
                    String currentStr = current.toString();
                    if (lruTracker.contains(currentStr)) {
                        lruTracker.use(currentStr);
                    }
                }

                // Update LFU: mark this code as recently used (only if it's already tracked)
                if (lfuPolicy) {
                    String currentStr = current.toString();
                    if (lfuTracker.contains(currentStr)) {
                        lfuTracker.use(currentStr);
                    }
                }

                if (nextCode < maxCode) {
                    if (nextCode >= (1 << W) && W < maxW) {
                        W++;
                        debug("Increased W to " + W);
                    }

                    // LRU policy: evict if at capacity BEFORE adding new entry
                    if (lruPolicy && nextCode == maxCode - 1) {
                        debug("LRU: At capacity, need to evict");
                        lruTracker.printState();
                        String lruEntry = lruTracker.findLRU();
                        if (lruEntry != null) {
                            debug("EVICTING: '" + escapeString(lruEntry) + "'");
                            dictionary.put(new StringBuilder(lruEntry), null);
                            lruTracker.remove(lruEntry);
                        }
                    }

                    // LFU policy: evict if at capacity BEFORE adding new entry
                    if (lfuPolicy && nextCode == maxCode - 1) {
                        debug("LFU: At capacity, need to evict");
                        lfuTracker.printState();
                        String lfuEntry = lfuTracker.findLFU();
                        if (lfuEntry != null) {
                            debug("EVICTING: '" + escapeString(lfuEntry) + "'");
                            dictionary.put(new StringBuilder(lfuEntry), null);
                            lfuTracker.remove(lfuEntry);
                        }
                    }

                    String nextStr = next.toString();
                    debug("ADDING to codebook: '" + escapeString(nextStr) + "' -> code " + nextCode);
                    dictionary.put(next, nextCode);

                    // Add new entry to LRU tracker
                    if (lruPolicy) {
                        lruTracker.use(nextStr);
                    }

                    // Add new entry to LFU tracker
                    if (lfuPolicy) {
                        lfuTracker.use(nextStr);
                    }

                    nextCode++;

                } else {
                    // Dictionary full
                    if (resetPolicy) {
                        debug("RESET policy: codebook full");
                        if (nextCode >= (1 << W) && W < maxW) {
                            W++;
                            debug("Increased W to " + W);
                        }
                        debug("OUTPUT RESET_CODE: " + RESET_CODE + " (W=" + W + " bits)");
                        BinaryStdOut.write(RESET_CODE, W);

                        debug("Resetting codebook to initial state");
                        dictionary = new TSTmod<>();
                        for (int i = 0; i < alphabetSize; i++)
                            dictionary.put(alphabetKeys[i], i);

                        nextCode = initialNextCode;
                        W = minW;
                        debug("Reset complete: nextCode=" + nextCode + ", W=" + W);
                    } else {
                        debug("FREEZE policy: codebook full, no action");
                    }
                }

                current = new StringBuilder().append(c);
                debug("current reset to: '" + escapeString(current.toString()) + "'");
            }
        }

        // Output final pattern
        debug("\n=== FINAL OUTPUT ===");
        if (current.length() > 0) {
            int outputCode = dictionary.get(current);
            debug("OUTPUT final: code=" + outputCode + " for '" + escapeString(current.toString()) + "' (W=" + W + " bits)");
            BinaryStdOut.write(outputCode, W);

            if (lruPolicy) {
                String currentStr = current.toString();
                if (lruTracker.contains(currentStr)) {
                    lruTracker.use(currentStr);
                }
            }

            if (lfuPolicy) {
                String currentStr = current.toString();
                if (lfuTracker.contains(currentStr)) {
                    lfuTracker.use(currentStr);
                }
            }
        }

        if (nextCode >= (1 << W) && W < maxW) {
            W++;
            debug("Increased W to " + W + " for EOF_CODE");
        }

        debug("OUTPUT EOF_CODE: " + EOF_CODE + " (W=" + W + " bits)");
        BinaryStdOut.write(EOF_CODE, W);

        if (lruPolicy) {
            debug("\nFinal LRU state:");
            lruTracker.printState();
        }

        if (lfuPolicy) {
            debug("\nFinal LFU state:");
            lfuTracker.printState();
        }

        debug("\n=== COMPRESSION COMPLETE ===\n");
        BinaryStdOut.close();
    }

    private static void expand() {
        debug("\n=== STARTING DECOMPRESSION ===");

        Header h = readHeader();

        int maxCode = 1 << h.maxW;
        int W = h.minW;
        int alphabetSize = h.alphabetSize;

        int EOF_CODE = h.alphabetSize;
        int RESET_CODE = -1;
        boolean resetPolicy = (h.policy == 1);
        boolean lruPolicy = (h.policy == 2);
        boolean lfuPolicy = (h.policy == 3);

        int initialNextCode;
        if (resetPolicy) {
            RESET_CODE = h.alphabetSize + 1;
            initialNextCode = h.alphabetSize + 2;
        } else {
            initialNextCode = h.alphabetSize + 1;
        }

        int nextCode = initialNextCode;

        debug("maxCode = " + maxCode);
        debug("EOF_CODE = " + EOF_CODE);
        if (resetPolicy) debug("RESET_CODE = " + RESET_CODE);
        debug("initialNextCode = " + initialNextCode);

        // LRU tracking structure for expansion
        LRUTrackerDecoder lruTracker = null;
        if (lruPolicy) {
            lruTracker = new LRUTrackerDecoder(maxCode);
        }

        // LFU tracking structure for expansion
        LFUTrackerDecoder lfuTracker = null;
        if (lfuPolicy) {
            lfuTracker = new LFUTrackerDecoder(maxCode);
        }

        String[] dictionary = new String[maxCode];
        debug("\nInitializing decoding table:");
        for (int i = 0; i < h.alphabetSize; i++) {
            dictionary[i] = h.alphabet.get(i).toString();
            debug("  code " + i + " -> '" + escapeString(dictionary[i]) + "'");
        }

        if (BinaryStdIn.isEmpty()) {
            debug("Input is empty, closing.");
            BinaryStdOut.close();
            return;
        }

        int prevCode = BinaryStdIn.readInt(W);
        debug("\nFirst codeword: " + prevCode + " (W=" + W + " bits)");

        if (prevCode == EOF_CODE) {
            debug("First code is EOF_CODE, closing.");
            BinaryStdOut.close();
            return;
        }

        if (prevCode < h.alphabetSize) {
            String val = dictionary[prevCode];
            debug("Decoded: '" + escapeString(val) + "'");
            debug("OUTPUT: '" + escapeString(val) + "'");
            BinaryStdOut.write(val);
        } else {
            System.err.println("Bad compressed code: " + prevCode);
            System.exit(1);
        }

        String valPrior = dictionary[prevCode];

        int step = 0;
        debug("\n=== DECODING LOOP ===");
        while (!BinaryStdIn.isEmpty()) {

            if (nextCode >= (1 << W) && W < h.maxW) {
                W++;
                debug("Increased W to " + W);
            }

            int codeword = BinaryStdIn.readInt(W);
            step++;
            debug("\n--- Step " + step + " ---");
            debug("Read codeword: " + codeword + " (W=" + W + " bits)");

            if (codeword == EOF_CODE) {
                debug("Received EOF_CODE, ending decompression");
                break;
            }

            if (resetPolicy && codeword == RESET_CODE) {
                debug("Received RESET_CODE, resetting decoding table");
                for (int i = h.alphabetSize; i < dictionary.length; i++) {
                    dictionary[i] = null;
                }

                nextCode = initialNextCode;
                W = h.minW;
                debug("Reset complete: nextCode=" + nextCode + ", W=" + W);

                codeword = BinaryStdIn.readInt(W);
                debug("Read post-reset codeword: " + codeword + " (W=" + W + " bits)");

                if (codeword == EOF_CODE) {
                    debug("Post-reset code is EOF_CODE, ending");
                    break;
                }

                valPrior = dictionary[codeword];
                debug("Decoded: '" + escapeString(valPrior) + "'");
                debug("OUTPUT: '" + escapeString(valPrior) + "'");
                BinaryStdOut.write(valPrior);

                continue;
            }

            String s;
            if (codeword < nextCode) {
                s = dictionary[codeword];
                debug("Codeword " + codeword + " -> '" + escapeString(s) + "'");
            } else if (codeword == nextCode) {
                s = valPrior + valPrior.charAt(0);
                debug("Codeword " + codeword + " not in table (special case): '" + escapeString(s) + "'");
            } else {
                System.err.println("Bad compressed code: " + codeword);
                System.exit(1);
                return; // unreachable but keeps compiler happy
            }

            debug("OUTPUT: '" + escapeString(s) + "'");
            BinaryStdOut.write(s);

            // Update LRU tracker for the used code (only track non-alphabet codes)
            if (lruPolicy && codeword >= alphabetSize + 1) {
                lruTracker.use(codeword);
            }

            // Update LFU tracker for the used code (only track non-alphabet codes)
            if (lfuPolicy && codeword >= alphabetSize + 1) {
                lfuTracker.use(codeword);
            }

            if (nextCode < maxCode) {
                // LRU: evict if at capacity BEFORE adding new entry
                if (lruPolicy && nextCode == maxCode - 1) {
                    debug("LRU: At capacity, need to evict");
                    lruTracker.printState();
                    int lruCode = lruTracker.findLRU();
                    if (lruCode != -1) {
                        debug("EVICTING: code=" + lruCode + " (was '" + escapeString(dictionary[lruCode]) + "')");
                        dictionary[lruCode] = null;
                        lruTracker.remove(lruCode);
                    }
                }

                // LFU: evict if at capacity BEFORE adding new entry
                if (lfuPolicy && nextCode == maxCode - 1) {
                    debug("LFU: At capacity, need to evict");
                    lfuTracker.printState();
                    int lfuCode = lfuTracker.findLFU();
                    if (lfuCode != -1) {
                        debug("EVICTING: code=" + lfuCode + " (was '" + escapeString(dictionary[lfuCode]) + "')");
                        dictionary[lfuCode] = null;
                        lfuTracker.remove(lfuCode);
                    }
                }

                String newEntry = valPrior + s.charAt(0);
                debug("ADDING to table: code " + nextCode + " -> '" + escapeString(newEntry) + "'");
                dictionary[nextCode] = newEntry;

                // Track in LRU
                if (lruPolicy) {
                    lruTracker.use(nextCode);
                }

                // Track in LFU
                if (lfuPolicy) {
                    lfuTracker.use(nextCode);
                }

                nextCode++;
            } else {
                debug("Table full (nextCode=" + nextCode + " >= maxCode=" + maxCode + ")");
            }

            valPrior = s;
        }

        if (lruPolicy) {
            debug("\nFinal LRU state:");
            lruTracker.printState();
        }

        if (lfuPolicy) {
            debug("\nFinal LFU state:");
            lfuTracker.printState();
        }

        debug("\n=== DECOMPRESSION COMPLETE ===\n");
        BinaryStdOut.close();
    }


    //============================================
    // Header Stuff
    // ===========================================
    
    private static class Header {
        int minW; 
        int maxW; 
        int policy; // Eviction policy code (0=freeze, 1=reset, 2=lru, 3=lfu)
        List<Character> alphabet; // char because we only use extended ascii as max dictionary, which fits in 1 byte
        int alphabetSize;
    }

    /**
     * Write the header to the compressed output stream.
     * The header contains all information needed to decompress the file:
     * - Codeword width parameters (minW, maxW)
     * - Eviction policy 
     * - The complete alphabet
     * 
     * 8 bits for each for convenience
     * 
     * Header format (in order):
     * 1. minW (8 bits, up to 2^8 = 256)
     * 2. maxW (8 bits, up to 2^8 = 256)
     * 3. policy code (8 bits): 0=freeze, 1=reset, 2=lru, 3=lfu
     * 4. alphabet size (16 bits): allows up to 65535 symbols
     * 5. alphabet symbols (8 bits each): raw byte value of each character
     * 
     * Alphabet needs only 1 byte, because the max alphabet we have is extended ascii
     *
     * @param minW     minimum codeword width in bits
     * @param maxW     maximum codeword width in bits
     * @param policy   eviction policy name ("freeze", "reset", "lru", or "lfu")
     * @param alphabet ordered list of single-character symbols
     */
    private static void writeHeader(int minW, int maxW, String policy, List<Character> alphabet) {
        // Write codeword width parameters
        BinaryStdOut.write(minW, 8);
        BinaryStdOut.write(maxW, 8);

        int policyCode;
        switch (policy) {
            case "freeze":
                policyCode = 0;
                break;
            case "reset":
                policyCode = 1;
                break;
            case "lru":
                policyCode = 2;
                break;
            case "lfu":
                policyCode = 3;
                break;
            default:
                policyCode = 0; // Default to freeze if unknown just in case
                break;
        }
        BinaryStdOut.write(policyCode, 8);

        // Write base alphabet size (allows decoder to know how many symbols to read)
        BinaryStdOut.write(alphabet.size(), 16);

        // Write each symbol as a single byte (char value)
        for (Character symbol : alphabet) {
             // added ternary 0 check, technically should never happen
            BinaryStdOut.write(symbol != null ? symbol : 0, 8);
        }
    }

    private static Header readHeader() {
        Header header = new Header();

        header.minW = BinaryStdIn.readInt(8);
        header.maxW = BinaryStdIn.readInt(8);

        header.policy = BinaryStdIn.readInt(8);

        int alphabetSize = BinaryStdIn.readInt(16);
        header.alphabetSize = alphabetSize;

        header.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++) {
            header.alphabet.add(BinaryStdIn.readChar(8));
        }

        return header;
    }

}
