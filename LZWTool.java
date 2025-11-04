// O(1) LRU LFU
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

    private static final boolean DEBUG = false; // Set to false to disable debug output

    // O(1) LRU tracking using doubly-linked list + HashMap for compression
    private static class LRUTracker {
        private class Node {
            String key;
            Node prev, next;
            Node(String key) { this.key = key; }
        }

        private final HashMap<String, Node> map;
        private final Node head, tail; // Sentinels: head.next = MRU, tail.prev = LRU

        LRUTracker(int capacity) {
            this.map = new HashMap<>(capacity);
            this.head = new Node(null);
            this.tail = new Node(null);
            head.next = tail;
            tail.prev = head;
            debug("LRUTracker initialized with capacity: " + capacity);
        }

        void use(String key) {
            Node node = map.get(key);
            if (node != null) {
                // Move to front (most recently used)
                removeNode(node);
                addToFront(node);
            } else {
                // New entry
                node = new Node(key);
                map.put(key, node);
                addToFront(node);
            }
            debug("LRUTracker.use('" + escapeString(key) + "'), mapSize=" + map.size());
        }

        String findLRU() {
            if (tail.prev == head) return null; // Empty
            String lruKey = tail.prev.key;
            debug("LRUTracker.findLRU() -> '" + escapeString(lruKey) + "'");
            return lruKey;
        }

        void remove(String key) {
            Node node = map.remove(key);
            if (node != null) {
                removeNode(node);
            }
            debug("LRUTracker.remove('" + escapeString(key) + "'), mapSize=" + map.size());
        }

        boolean contains(String key) {
            return map.containsKey(key);
        }

        private void addToFront(Node node) {
            node.next = head.next;
            node.prev = head;
            head.next.prev = node;
            head.next = node;
        }

        private void removeNode(Node node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }

        void printState() {
            debug("LRUTracker state (size=" + map.size() + "):");
            Node current = head.next;
            while (current != tail) {
                debug("  '" + escapeString(current.key) + "'");
                current = current.next;
            }
        }
    }

    // O(1) LRU tracker for decoder (uses Integer keys for codes)
    private static class LRUTrackerDecoder {
        private class Node {
            int code;
            Node prev, next;
            Node(int code) { this.code = code; }
        }

        private final HashMap<Integer, Node> map;
        private final Node head, tail; // Sentinels: head.next = MRU, tail.prev = LRU

        LRUTrackerDecoder(int capacity) {
            this.map = new HashMap<>(capacity);
            this.head = new Node(-1);
            this.tail = new Node(-1);
            head.next = tail;
            tail.prev = head;
            debug("LRUTrackerDecoder initialized with capacity: " + capacity);
        }

        void use(int code) {
            Node node = map.get(code);
            if (node != null) {
                // Move to front (most recently used)
                removeNode(node);
                addToFront(node);
            } else {
                // New entry
                node = new Node(code);
                map.put(code, node);
                addToFront(node);
            }
            debug("LRUTrackerDecoder.use(code=" + code + "), mapSize=" + map.size());
        }

        int findLRU() {
            if (tail.prev == head) return -1; // Empty
            int lruCode = tail.prev.code;
            debug("LRUTrackerDecoder.findLRU() -> code=" + lruCode);
            return lruCode;
        }

        void remove(int code) {
            Node node = map.remove(code);
            if (node != null) {
                removeNode(node);
            }
            debug("LRUTrackerDecoder.remove(code=" + code + "), mapSize=" + map.size());
        }

        private void addToFront(Node node) {
            node.next = head.next;
            node.prev = head;
            head.next.prev = node;
            head.next = node;
        }

        private void removeNode(Node node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }

        void printState() {
            debug("LRUTrackerDecoder state (size=" + map.size() + "):");
            Node current = head.next;
            while (current != tail) {
                debug("  code=" + current.code);
                current = current.next;
            }
        }
    }

    // O(1) LFU tracking using frequency buckets + doubly-linked lists
    private static class LFUTracker {
        private class Node {
            String key;
            int freq;
            Node prev, next;
            Node(String key, int freq) { this.key = key; this.freq = freq; }
        }

        private class FreqList {
            Node head, tail; // Sentinels
            FreqList() {
                head = new Node(null, 0);
                tail = new Node(null, 0);
                head.next = tail;
                tail.prev = head;
            }
            void addToFront(Node node) {
                node.next = head.next;
                node.prev = head;
                head.next.prev = node;
                head.next = node;
            }
            void remove(Node node) {
                node.prev.next = node.next;
                node.next.prev = node.prev;
            }
            boolean isEmpty() {
                return head.next == tail;
            }
            Node getFirst() {
                return head.next == tail ? null : head.next;
            }
        }

        private final HashMap<String, Node> keyToNode;
        private final HashMap<Integer, FreqList> freqToList;
        private int minFreq;

        LFUTracker(int capacity) {
            this.keyToNode = new HashMap<>(capacity);
            this.freqToList = new HashMap<>();
            this.minFreq = 0;
            debug("LFUTracker initialized with capacity: " + capacity);
        }

        void use(String key) {
            Node node = keyToNode.get(key);
            if (node == null) {
                // New key: add with frequency 1
                node = new Node(key, 1);
                keyToNode.put(key, node);
                freqToList.computeIfAbsent(1, k -> new FreqList()).addToFront(node);
                minFreq = 1;
                debug("LFUTracker.use('" + escapeString(key) + "') NEW freq=1, mapSize=" + keyToNode.size());
            } else {
                // Existing key: increment frequency
                int oldFreq = node.freq;
                FreqList oldList = freqToList.get(oldFreq);
                oldList.remove(node);

                // Update minFreq if needed
                if (oldFreq == minFreq && oldList.isEmpty()) {
                    minFreq = oldFreq + 1;
                }

                node.freq++;
                freqToList.computeIfAbsent(node.freq, k -> new FreqList()).addToFront(node);
                debug("LFUTracker.use('" + escapeString(key) + "') freq=" + node.freq + ", mapSize=" + keyToNode.size());
            }
        }

        String findLFU() {
            FreqList minList = freqToList.get(minFreq);
            if (minList == null || minList.isEmpty()) return null;
            Node lfuNode = minList.getFirst();
            debug("LFUTracker.findLFU() -> '" + escapeString(lfuNode.key) + "' with freq=" + lfuNode.freq);
            return lfuNode.key;
        }

        void remove(String key) {
            Node node = keyToNode.remove(key);
            if (node != null) {
                FreqList list = freqToList.get(node.freq);
                list.remove(node);
                debug("LFUTracker.remove('" + escapeString(key) + "'), mapSize=" + keyToNode.size());
            }
        }

        boolean contains(String key) {
            return keyToNode.containsKey(key);
        }

        void printState() {
            debug("LFUTracker state (size=" + keyToNode.size() + ", minFreq=" + minFreq + "):");
            for (Map.Entry<Integer, FreqList> entry : freqToList.entrySet()) {
                int freq = entry.getKey();
                FreqList list = entry.getValue();
                Node current = list.head.next;
                while (current != list.tail) {
                    debug("  '" + escapeString(current.key) + "' -> freq=" + freq);
                    current = current.next;
                }
            }
        }
    }

    // O(1) LFU tracker for decoder (uses Integer keys for codes)
    private static class LFUTrackerDecoder {
        private class Node {
            int code;
            int freq;
            Node prev, next;
            Node(int code, int freq) { this.code = code; this.freq = freq; }
        }

        private class FreqList {
            Node head, tail; // Sentinels
            FreqList() {
                head = new Node(-1, 0);
                tail = new Node(-1, 0);
                head.next = tail;
                tail.prev = head;
            }
            void addToFront(Node node) {
                node.next = head.next;
                node.prev = head;
                head.next.prev = node;
                head.next = node;
            }
            void remove(Node node) {
                node.prev.next = node.next;
                node.next.prev = node.prev;
            }
            boolean isEmpty() {
                return head.next == tail;
            }
            Node getFirst() {
                return head.next == tail ? null : head.next;
            }
        }

        private final HashMap<Integer, Node> codeToNode;
        private final HashMap<Integer, FreqList> freqToList;
        private int minFreq;

        LFUTrackerDecoder(int capacity) {
            this.codeToNode = new HashMap<>(capacity);
            this.freqToList = new HashMap<>();
            this.minFreq = 0;
            debug("LFUTrackerDecoder initialized with capacity: " + capacity);
        }

        void use(int code) {
            Node node = codeToNode.get(code);
            if (node == null) {
                // New code: add with frequency 1
                node = new Node(code, 1);
                codeToNode.put(code, node);
                freqToList.computeIfAbsent(1, k -> new FreqList()).addToFront(node);
                minFreq = 1;
                debug("LFUTrackerDecoder.use(code=" + code + ") NEW freq=1, mapSize=" + codeToNode.size());
            } else {
                // Existing code: increment frequency
                int oldFreq = node.freq;
                FreqList oldList = freqToList.get(oldFreq);
                oldList.remove(node);

                // Update minFreq if needed
                if (oldFreq == minFreq && oldList.isEmpty()) {
                    minFreq = oldFreq + 1;
                }

                node.freq++;
                freqToList.computeIfAbsent(node.freq, k -> new FreqList()).addToFront(node);
                debug("LFUTrackerDecoder.use(code=" + code + ") freq=" + node.freq + ", mapSize=" + codeToNode.size());
            }
        }

        int findLFU() {
            FreqList minList = freqToList.get(minFreq);
            if (minList == null || minList.isEmpty()) return -1;
            Node lfuNode = minList.getFirst();
            debug("LFUTrackerDecoder.findLFU() -> code=" + lfuNode.code + " with freq=" + lfuNode.freq);
            return lfuNode.code;
        }

        void remove(int code) {
            Node node = codeToNode.remove(code);
            if (node != null) {
                FreqList list = freqToList.get(node.freq);
                list.remove(node);
                debug("LFUTrackerDecoder.remove(code=" + code + "), mapSize=" + codeToNode.size());
            }
        }

        void printState() {
            debug("LFUTrackerDecoder state (size=" + codeToNode.size() + ", minFreq=" + minFreq + "):");
            for (Map.Entry<Integer, FreqList> entry : freqToList.entrySet()) {
                int freq = entry.getKey();
                FreqList list = entry.getValue();
                Node current = list.head.next;
                while (current != list.tail) {
                    debug("  code=" + current.code + " -> freq=" + freq);
                    current = current.next;
                }
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

        // O(1) alphabet validation using boolean array instead of HashSet
        boolean[] validChar = new boolean[256]; // Extended ASCII
        for (Character symbol : alphabet) {
            validChar[symbol] = true;
        }

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
        if (!validChar[c]) {
            System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
            System.exit(1);
        }
        StringBuilder current = new StringBuilder().append(c);
        debug("\nFirst character: '" + escapeString(String.valueOf(c)) + "'");

        int step = 0;
        debug("\n=== ENCODING LOOP ===");

        // Reusable StringBuilder for concatenation
        StringBuilder nextBuilder = new StringBuilder(256);

        // Cache bit width threshold to avoid recalculating (1 << W)
        int widthThreshold = 1 << W;

        while (!BinaryStdIn.isEmpty()) {

            c = BinaryStdIn.readChar();
            if (!validChar[c]) {
                System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
                System.exit(1);
            }

            // Reuse nextBuilder instead of creating new StringBuilder
            nextBuilder.setLength(0);
            nextBuilder.append(current).append(c);

            step++;
            debug("\n--- Step " + step + " ---");
            debug("Read char: '" + escapeString(String.valueOf(c)) + "'");
            debug("current = '" + escapeString(current.toString()) + "', next = '" + escapeString(nextBuilder.toString()) + "'");

            if (dictionary.contains(nextBuilder)) {
                debug("Codebook CONTAINS '" + escapeString(nextBuilder.toString()) + "' - extending current");
                // Copy nextBuilder to current
                current.setLength(0);
                current.append(nextBuilder);
            } else {
                // Output current pattern
                int outputCode = dictionary.get(current);
                debug("Codebook does NOT contain '" + escapeString(nextBuilder.toString()) + "'");
                debug("OUTPUT: code=" + outputCode + " for '" + escapeString(current.toString()) + "' (W=" + W + " bits)");
                BinaryStdOut.write(outputCode, W);

                // Cache string conversions (used multiple times)
                String currentStr = null;
                if (lruPolicy || lfuPolicy) {
                    currentStr = current.toString();
                }

                // Update LRU: mark this code as recently used (only if it's already tracked)
                if (lruPolicy && lruTracker.contains(currentStr)) {
                    lruTracker.use(currentStr);
                }

                // Update LFU: mark this code as recently used (only if it's already tracked)
                if (lfuPolicy && lfuTracker.contains(currentStr)) {
                    lfuTracker.use(currentStr);
                }

                if (nextCode < maxCode) {
                    if (nextCode >= widthThreshold && W < maxW) {
                        W++;
                        widthThreshold = 1 << W;  // Update cached threshold
                        debug("Increased W to " + W);
                    }

                    // LRU policy: evict if at capacity BEFORE adding new entry
                    if (lruPolicy && nextCode == maxCode - 1) {
                        debug("LRU: At capacity, need to evict");
                        lruTracker.printState();
                        String lruEntry = lruTracker.findLRU();
                        if (lruEntry != null) {
                            debug("EVICTING: '" + escapeString(lruEntry) + "'");
                            // Reuse nextBuilder for eviction lookup
                            nextBuilder.setLength(0);
                            nextBuilder.append(lruEntry);
                            dictionary.put(nextBuilder, null);
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
                            // Reuse nextBuilder for eviction lookup
                            nextBuilder.setLength(0);
                            nextBuilder.append(lfuEntry);
                            dictionary.put(nextBuilder, null);
                            lfuTracker.remove(lfuEntry);
                        }
                    }

                    // Now add the new entry (nextBuilder already contains current + c)
                    nextBuilder.setLength(0);
                    nextBuilder.append(current).append(c);
                    String nextStr = nextBuilder.toString();

                    debug("ADDING to codebook: '" + escapeString(nextStr) + "' -> code " + nextCode);
                    // Create new StringBuilder for dictionary storage
                    dictionary.put(new StringBuilder(nextStr), nextCode);

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
                        if (nextCode >= widthThreshold && W < maxW) {
                            W++;
                            widthThreshold = 1 << W;
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
                        widthThreshold = 1 << W;  // Reset cached threshold
                        debug("Reset complete: nextCode=" + nextCode + ", W=" + W);
                    } else {
                        debug("FREEZE policy: codebook full, no action");
                    }
                }

                // Reset current to single character
                current.setLength(0);
                current.append(c);
                debug("current reset to: '" + escapeString(current.toString()) + "'");
            }
        }

        // Output final pattern
        debug("\n=== FINAL OUTPUT ===");
        if (current.length() > 0) {
            int outputCode = dictionary.get(current);
            debug("OUTPUT final: code=" + outputCode + " for '" + escapeString(current.toString()) + "' (W=" + W + " bits)");
            BinaryStdOut.write(outputCode, W);

            // Cache string conversion
            String currentStr = null;
            if (lruPolicy || lfuPolicy) {
                currentStr = current.toString();
            }

            if (lruPolicy && lruTracker.contains(currentStr)) {
                lruTracker.use(currentStr);
            }

            if (lfuPolicy && lfuTracker.contains(currentStr)) {
                lfuTracker.use(currentStr);
            }
        }

        if (nextCode >= widthThreshold && W < maxW) {
            W++;
            widthThreshold = 1 << W;
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

        // Cache bit width threshold to avoid recalculating (1 << W)
        int widthThreshold = 1 << W;

        while (!BinaryStdIn.isEmpty()) {

            if (nextCode >= widthThreshold && W < h.maxW) {
                W++;
                widthThreshold = 1 << W;
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
                widthThreshold = 1 << W;  // Reset cached threshold
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

            // Update LRU tracker for the used code AFTER adding new entry (only track non-alphabet codes)
            if (lruPolicy && codeword >= alphabetSize + 1) {
                lruTracker.use(codeword);
            }

            // Update LFU tracker for the used code AFTER adding new entry (only track non-alphabet codes)
            if (lfuPolicy && codeword >= alphabetSize + 1) {
                lfuTracker.use(codeword);
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


