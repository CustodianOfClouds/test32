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

    // Add these as class-level fields near the top of LZWTool class
    private static class LRUNode {
        StringBuilder key;
        int code;
        LRUNode prev;
        LRUNode next;
        
        LRUNode(StringBuilder key, int code) {
            this.key = new StringBuilder(key);
            this.code = code;
        }
    }

    private static class LRUQueue {
        LRUNode head; // Most recently used
        LRUNode tail; // Least recently used
        HashMap<Integer, LRUNode> indirectionTable; // code -> node
        int alphabetSize;
        boolean debugMode = false;

        LRUQueue(int alphabetSize) {
            this.alphabetSize = alphabetSize;
            this.indirectionTable = new HashMap<>();
        }

        // Enable debug mode
        void setDebugMode(boolean debug) {
            this.debugMode = debug;
        }

        // Print the LRU queue state
        String getQueueState() {
            StringBuilder sb = new StringBuilder("[");
            LRUNode current = head;
            while (current != null) {
                sb.append(current.code).append(":\"").append(current.key).append("\"");
                if (current.next != null) {
                    sb.append(", ");
                }
                current = current.next;
            }
            sb.append("]");
            return sb.toString();
        }
        
        // Add a new entry to the head (most recent)
        void addToHead(StringBuilder key, int code) {
            // Don't track alphabet entries
            if (code < alphabetSize) return;

            if (debugMode) {
                System.err.println("  LRU ADD: code=" + code + " key=\"" + key + "\"");
            }

            LRUNode node = new LRUNode(key, code);
            indirectionTable.put(code, node);

            if (head == null) {
                head = tail = node;
            } else {
                node.next = head;
                head.prev = node;
                head = node;
            }

            if (debugMode) {
                System.err.println("  LRU Queue after ADD: " + getQueueState());
            }
        }
        
        // Move an existing node to the head (mark as recently used)
        void moveToHead(int code) {
            // Don't track alphabet entries
            if (code < alphabetSize) return;

            if (debugMode) {
                System.err.println("  LRU MOVE: code=" + code);
            }

            LRUNode node = indirectionTable.get(code);
            if (node == null) {
                if (debugMode) {
                    System.err.println("  LRU MOVE: code not in queue (no-op)");
                }
                return;
            }
            if (node == head) {
                if (debugMode) {
                    System.err.println("  LRU MOVE: already at head (no-op)");
                }
                return;
            }

            // Remove from current position
            if (node.prev != null) {
                node.prev.next = node.next;
            }
            if (node.next != null) {
                node.next.prev = node.prev;
            }
            if (node == tail) {
                tail = node.prev;
            }

            // Add to head
            node.prev = null;
            node.next = head;
            head.prev = node;
            head = node;

            if (debugMode) {
                System.err.println("  LRU Queue after MOVE: " + getQueueState());
            }
        }
        
        // Remove and return the least recently used entry
        LRUNode removeTail() {
            if (tail == null) return null;

            if (debugMode) {
                System.err.println("  LRU EVICT: code=" + tail.code + " key=\"" + tail.key + "\"");
            }

            LRUNode lru = tail;
            indirectionTable.remove(lru.code);

            if (tail.prev != null) {
                tail = tail.prev;
                tail.next = null;
            } else {
                head = tail = null;
            }

            if (debugMode) {
                System.err.println("  LRU Queue after EVICT: " + getQueueState());
            }

            return lru;
        }
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

        writeHeader(minW, maxW, policy, alphabet);

        // compression dictionary stored in TST, String -> Code/Integer
        TSTmod<Integer> dictionary = new TSTmod<>();

        // width tracking
        int W = minW;
        int maxCode = 1 << maxW;

        Set<Character> alphabetSet = new HashSet<>(alphabet);
        int nextCode = 0;
        StringBuilder sb = new StringBuilder(1);
        for (Character symbol : alphabet) {
            sb.setLength(0);
            sb.append(symbol);
            dictionary.put(sb, nextCode++);
        }

        int EOF_CODE = nextCode++;

        int RESET_CODE = -1;
        if (policy.equals("reset")) {
            RESET_CODE = nextCode++;
        }

        // LRU tracking structure
        LRUQueue lruQueue = null;
        if (policy.equals("lru")) {
            lruQueue = new LRUQueue(alphabet.size());
            lruQueue.setDebugMode(true); // Enable debug mode for LRU
            System.err.println("=== COMPRESSION DEBUG MODE ===");
            System.err.println("Alphabet size: " + alphabet.size());
            System.err.println("minW: " + minW + ", maxW: " + maxW + ", maxCode: " + maxCode);
        }

        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        char c = BinaryStdIn.readChar();
        if (!alphabetSet.contains(c)) {
            System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
            System.exit(1);
        }
        StringBuilder current = new StringBuilder().append(c);

        while (!BinaryStdIn.isEmpty()) {

            c = BinaryStdIn.readChar();
            if (!alphabetSet.contains(c)) {
                System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
                System.exit(1);
            }
            StringBuilder next = new StringBuilder(current).append(c);
            
            if (dictionary.contains(next)) {
                current = next;
            } else {
                // Output current pattern
                int outputCode = dictionary.get(current);

                if (lruQueue != null) {
                    System.err.println("\n[COMPRESS] Output code=" + outputCode + " for \"" + current + "\" (W=" + W + ")");
                }

                BinaryStdOut.write(outputCode, W);

                // Update LRU: mark this code as recently used
                if (lruQueue != null) {
                    lruQueue.moveToHead(outputCode);
                }

                if (nextCode < maxCode) {
                    if (nextCode >= (1 << W) && W < maxW) {
                        W++;
                    }

                    if (lruQueue != null) {
                        System.err.println("[COMPRESS] Add to dictionary: \"" + next + "\" -> code=" + nextCode);
                    }

                    dictionary.put(next, nextCode);

                    // Add new entry to LRU queue
                    if (lruQueue != null) {
                        lruQueue.addToHead(next, nextCode);
                    }

                    nextCode++;

                } else {
                    // Dictionary full
                    switch (policy) {
                        case "freeze":
                            break;
                        case "reset":
                            if (nextCode >= (1 << W) && W < maxW) {
                                W++;
                            }
                            BinaryStdOut.write(RESET_CODE, W);

                            dictionary = new TSTmod<>();
                            nextCode = 0;
                            sb.setLength(0);
                            for (Character symbol : alphabet) {
                                sb.setLength(0);
                                sb.append(symbol);
                                dictionary.put(sb, nextCode++);
                            }
                            nextCode += 2;
                            W = minW;
                            break;
                        case "lru":
                            System.err.println("[COMPRESS] Dictionary FULL, evicting LRU entry...");
                            // Evict least recently used entry
                            LRUNode lru = lruQueue.removeTail();
                            if (lru != null) {
                                System.err.println("[COMPRESS] Evicted code=" + lru.code + " (was \"" + lru.key + "\")");
                                // Remove from dictionary
                                dictionary.put(lru.key, null);

                                // Add new entry with the evicted code
                                System.err.println("[COMPRESS] Reuse code=" + lru.code + " for \"" + next + "\"");
                                dictionary.put(next, lru.code);
                                lruQueue.addToHead(next, lru.code);
                            }
                            break;
                        default:
                            break;
                    }
                }
            
                current = new StringBuilder().append(c);
            }
        }

        // Output final pattern
        if (current.length() > 0) {
            int outputCode = dictionary.get(current);

            if (lruQueue != null) {
                System.err.println("\n[COMPRESS] Output FINAL code=" + outputCode + " for \"" + current + "\" (W=" + W + ")");
            }

            BinaryStdOut.write(outputCode, W);

            if (lruQueue != null) {
                lruQueue.moveToHead(outputCode);
            }
        }

        if (nextCode >= (1 << W) && W < maxW) {
            W++;
        }

        if (lruQueue != null) {
            System.err.println("\n[COMPRESS] Output EOF code=" + EOF_CODE + " (W=" + W + ")");
            System.err.println("=== COMPRESSION COMPLETE ===\n");
        }

        BinaryStdOut.write(EOF_CODE, W);
        BinaryStdOut.close();
    }

    private static void expand() { 

        Header h = readHeader();

        int maxCode = 1 << h.maxW; 
        int W = h.minW;

        int EOF_CODE = h.alphabetSize;
        int nextCode = h.alphabetSize + 1;
        int RESET_CODE = -1;
        if (h.policy == 1) {
            RESET_CODE = h.alphabetSize + 1;
            nextCode++;
        }

        // LRU tracking structure for expansion
        LRUQueue lruQueue = null;
        if (h.policy == 2) { // LRU policy
            lruQueue = new LRUQueue(h.alphabetSize);
            lruQueue.setDebugMode(true); // Enable debug mode for LRU
            System.err.println("=== DECOMPRESSION DEBUG MODE ===");
            System.err.println("Alphabet size: " + h.alphabetSize);
            System.err.println("minW: " + h.minW + ", maxW: " + h.maxW + ", maxCode: " + maxCode);
        }

        String[] dictionary = new String[maxCode];
        for (int i = 0; i < h.alphabetSize; i++) {
            dictionary[i] = h.alphabet.get(i).toString();
        }

        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        int current = BinaryStdIn.readInt(W);
        if (current == EOF_CODE) {
            BinaryStdOut.close();
            return;
        }

        if (lruQueue != null) {
            System.err.println("\n[EXPAND] Read FIRST code=" + current + " (W=" + W + ")");
        }

        if (current < h.alphabetSize) {
            if (lruQueue != null) {
                System.err.println("[EXPAND] Output \"" + dictionary[current] + "\"");
            }
            BinaryStdOut.write(dictionary[current]);
        } else {
            System.err.println("Bad compressed code: " + current);
            System.exit(1);
        }

        // Mark as recently used
        if (lruQueue != null) {
            lruQueue.moveToHead(current);
        }

        String valPrior = dictionary[current];
        int codePrior = current; // Track PREVIOUS code for one-step-behind LRU

        while (!BinaryStdIn.isEmpty()) { 

            if (nextCode >= (1 << W) && W < h.maxW) {
                W++;
            }
            
            current = BinaryStdIn.readInt(W);

            if (lruQueue != null) {
                System.err.println("\n[EXPAND] Read code=" + current + " (W=" + W + ", nextCode=" + nextCode + ")");
            }

            if (current == EOF_CODE) {
                if (lruQueue != null) {
                    System.err.println("[EXPAND] EOF reached");
                }
                break;
            }

            if (h.policy == 1 && current == RESET_CODE) {
                for (int i = h.alphabetSize; i < dictionary.length; i++) {
                    dictionary[i] = null;
                }

                nextCode = h.alphabetSize + 2;
                W = h.minW;

                current = BinaryStdIn.readInt(W);

                if (current == EOF_CODE) {
                    break;
                }

                valPrior = dictionary[current];
                codePrior = current;
                BinaryStdOut.write(valPrior);

                continue;
            }

            String s = "";
            boolean isSpecialCase = (current == nextCode);

            if (current < nextCode) {
                s = dictionary[current];
                if (lruQueue != null) {
                    System.err.println("[EXPAND] Code in dictionary: \"" + s + "\"");
                }
            } else if (isSpecialCase) {
                StringBuilder tempSb = new StringBuilder(valPrior.length() + 1);
                tempSb.append(valPrior).append(valPrior.charAt(0));
                s = tempSb.toString();
                if (lruQueue != null) {
                    System.err.println("[EXPAND] Special case (code==nextCode): s = valPrior + valPrior[0] = \"" + s + "\"");
                }
            } else {
                System.err.println("Bad compressed code: " + current);
                System.exit(1);
            }

            if (lruQueue != null) {
                System.err.println("[EXPAND] Output \"" + s + "\"");
            }
            BinaryStdOut.write(s);

            // One-step-behind: Move PREVIOUS code BEFORE adding new entry
            // This happens for ALL cases including special case
            if (lruQueue != null) {
                System.err.println("[EXPAND] Move PREVIOUS code=" + codePrior);
                lruQueue.moveToHead(codePrior);
            }

            if (nextCode < maxCode) {
                // Add new entry to dictionary
                StringBuilder tempSb = new StringBuilder(valPrior.length() + 1);
                tempSb.append(valPrior).append(s.charAt(0));
                String newEntry = tempSb.toString();

                if (lruQueue != null) {
                    System.err.println("[EXPAND] Add to dictionary: \"" + newEntry + "\" -> code=" + nextCode);
                }

                dictionary[nextCode] = newEntry;

                if (lruQueue != null) {
                    lruQueue.addToHead(new StringBuilder(newEntry), nextCode);
                }

                nextCode++;

            } else {
                // Dictionary is full, need to evict
                switch (h.policy) {
                    case 0: // freeze
                        // Already moved codePrior above
                        break;
                    case 1: // reset
                        // Already moved codePrior above
                        break;
                    case 2: // lru
                        // Already moved codePrior above (to protect from eviction)
                        System.err.println("[EXPAND] Dictionary FULL, evicting LRU entry...");
                        System.err.println("[EXPAND] valPrior=\"" + valPrior + "\", s=\"" + s + "\", s[0]='" + s.charAt(0) + "'");
                        LRUNode lru = lruQueue.removeTail();
                        if (lru != null) {
                            System.err.println("[EXPAND] Evicted code=" + lru.code + " (was \"" + lru.key + "\")");
                            StringBuilder tempSb = new StringBuilder(valPrior.length() + 1);
                            tempSb.append(valPrior).append(s.charAt(0));
                            String newEntry = tempSb.toString();
                            System.err.println("[EXPAND] Reuse code=" + lru.code + " for \"" + newEntry + "\"");
                            dictionary[lru.code] = newEntry;
                            lruQueue.addToHead(new StringBuilder(newEntry), lru.code);
                        }
                        break;
                    case 3: // lfu
                        // Already moved codePrior above
                        break;
                    default:
                        break;
                }
            }

            valPrior = s;
            codePrior = current; // Always update for next iteration
        }

        if (lruQueue != null) {
            System.err.println("\n=== DECOMPRESSION COMPLETE ===\n");
        }

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
