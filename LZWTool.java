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

    /**
     * Helper method to increment ages of all entries in LRU map.
     * This is called whenever we use an entry or add a new entry.
     *
     * @param LRUMap map tracking pattern -> age
     */
    private static void incrementAllAges(Map<String, Integer> LRUMap) {
        for (String key : LRUMap.keySet()) {
            LRUMap.put(key, LRUMap.get(key) + 1);
        }
    }

    /**
     * Updates LRU tracking when a pattern is used (output during encoding).
     * Sets the used pattern's age to 0 and increments all other ages.
     * Only tracks patterns added to dictionary, not initial alphabet entries.
     *
     * @param LRUMap map tracking pattern -> age
     * @param usedPattern the pattern that was just used/output
     */
    private static void updateLRUOnUse(Map<String, Integer> LRUMap, String usedPattern) {
        // First increment all ages
        incrementAllAges(LRUMap);
        // Then set the used pattern to age 0
        if (LRUMap.containsKey(usedPattern)) {
            LRUMap.put(usedPattern, 0);
        }
    }

    /**
     * Updates LRU tracking when a new pattern is added to the dictionary.
     * Increments all existing ages, then adds the new pattern with age 0.
     *
     * @param LRUMap map tracking pattern -> age
     * @param newPattern the pattern being added
     */
    private static void updateLRUOnAdd(Map<String, Integer> LRUMap, String newPattern) {
        // First increment all ages
        incrementAllAges(LRUMap);
        // Then add new pattern with age 0
        LRUMap.put(newPattern, 0);
    }

    /**
     * Finds and returns the least recently used (oldest) entry in the LRU map.
     * Returns the string with the maximum age value.
     *
     * @param LRUMap map tracking pattern -> age
     * @return the pattern string with the highest age (LRU entry)
     */
    private static String findLRUEntry(Map<String, Integer> LRUMap) {
        String lruEntry = null;
        int maxAge = -1;

        for (Map.Entry<String, Integer> entry : LRUMap.entrySet()) {
            if (entry.getValue() > maxAge) {
                maxAge = entry.getValue();
                lruEntry = entry.getKey();
            }
        }

        return lruEntry;
    }

    private static void compress(int minW, int maxW, String policy, List<Character> alphabet) {

        writeHeader(minW, maxW, policy, alphabet);

        // compression dictionary stored in TST, String -> Code/Integer
        TSTmod<Integer> dictionary = new TSTmod<>();

        // width tracking
        int W = minW; // Current codeword width in bits
        int maxCode = 1 << maxW; // Maximum number of codes (2^maxW)

        // Create a fast lookup set for alphabet validation (avoids creating temporary StringBuilders)
        Set<Character> alphabetSet = new HashSet<>(alphabet);
        // create initial dictionary from alphabet
        // nextcode just tracks next available index that we can assign to new dictionary entries
        int nextCode = 0;
        // Reusable StringBuilder for initialization - avoids creating new StringBuilder for each symbol
        StringBuilder sb = new StringBuilder(1);
        for (Character symbol : alphabet) {
            sb.setLength(0);
            sb.append(symbol);
            dictionary.put(sb, nextCode++);
        }

        // Reserve nextCode for EOF, so actual codes start at nextCode + 1
        int EOF_CODE = nextCode++;// Skip EOF code

        //=== Reset Policy Setup ===
        int RESET_CODE = -1; // -1 unless reset is active then its nextCode + 2 after EOF
        if (policy.equals("reset")) {
            RESET_CODE = nextCode++;
            // we note the initialnextcode is alphabet.size() + 1 
            // eof is alphabet.size()
        }

        // LRU/LFU tracking structures
        // LRU: Map pattern string -> age (0 = most recently used, higher = older)
        // Excludes alphabet entries (we never evict those)
        Map<String, Integer> LRUMap = new HashMap<>();
        Map<String, Integer> LFUMap = new HashMap<>();

        // now let's start compressing!
        // raw binary data is piped in during actual execution, so we start reading from BinaryStdIn

        // Handle empty input
        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        // initialize the current pattern
        char c = BinaryStdIn.readChar();
        // Verify character is in alphabet using fast HashSet lookup (no StringBuilder allocation)
        if (!alphabetSet.contains(c)) {
            System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
            System.exit(1);
        }
        StringBuilder current = new StringBuilder().append(c);

        while (!BinaryStdIn.isEmpty()) {

            c = BinaryStdIn.readChar();
            // verify character is in alphabet using fast HashSet lookup (no StringBuilder allocation)
            if (!alphabetSet.contains(c)) {
                System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
                System.exit(1);
            }
            StringBuilder next = new StringBuilder(current).append(c);
            
            if (dictionary.contains(next)) {
                // Pattern exists - extend current pattern
                current = next;

            } else {

                // Pattern not in codebook - output current and add new pattern
                BinaryStdOut.write(dictionary.get(current), W);

                // Update LRU: mark output pattern as used (if it's in LRU map)
                if (policy.equals("lru")) {
                    String currentStr = current.toString();
                    if (LRUMap.containsKey(currentStr)) {
                        incrementAllAges(LRUMap);
                        LRUMap.put(currentStr, 0);
                        System.err.println("[ENCODE] Used pattern '" + currentStr + "' - age reset to 0");
                    }
                }

                // log into dictionary, if space available
                if (nextCode < maxCode) {

                    // Check if width needs to increase BEFORE adding new entry
                    if (nextCode >= (1 << W) && W < maxW) {
                        W++;
                    }

                    // There's space in the dictionary - add new pattern
                    String nextStr = next.toString();
                    dictionary.put(next, nextCode++);

                    // Update LRU tracking: add new pattern with age 0, increment all others
                    if (policy.equals("lru")) {
                        updateLRUOnAdd(LRUMap, nextStr);
                        System.err.println("[ENCODE] Added pattern '" + nextStr + "' with age 0, incremented all others");
                        System.err.println("[ENCODE] LRU Map state: " + LRUMap);
                    }

                } else {
                    // Dictionary full - handle according to policy
                    switch (policy) {
                        case "freeze":
                            // Do nothing - dictionary remains full
                            break;
                        case "reset":
                            //First, check if W needs to increase before writing RESET_CODE
                            // RESET_CODE is a regular codeword and follows same width rules
                            if (nextCode >= (1 << W) && W < maxW) {
                                W++;
                            }

                            BinaryStdOut.write(RESET_CODE, W);

                            // reset nextcode and reinitialize dictionary
                            dictionary = new TSTmod<>();
                            nextCode = 0;
                            // Reuse StringBuilder for efficiency (avoid allocating for each symbol)
                            sb.setLength(0);
                            for (Character symbol : alphabet) {
                                sb.setLength(0);
                                sb.append(symbol);
                                dictionary.put(sb, nextCode++);
                            }
                            // now set nextcode to be after eof and reset code
                            nextCode+=2; // skip eof and reset code
                            W = minW; // Reset codeword width

                            // Clear LRU tracking on reset
                            LRUMap.clear();
                            break;
                        case "lru":
                            // Find the least recently used entry (highest age)
                            String lruPattern = findLRUEntry(LRUMap);

                            if (lruPattern != null) {
                                System.err.println("[ENCODE] Dictionary full! Evicting LRU pattern: '" + lruPattern + "' with age " + LRUMap.get(lruPattern));

                                // Get the code of the LRU entry before removing it
                                StringBuilder lruBuilder = new StringBuilder(lruPattern);
                                Integer evictedCode = dictionary.get(lruBuilder);

                                // Remove the LRU entry from TST (put null to delete)
                                dictionary.put(lruBuilder, null);

                                // Remove from LRU tracking
                                LRUMap.remove(lruPattern);

                                // Now add the new pattern with the evicted code (reuse the slot)
                                String nextStr = next.toString();

                                dictionary.put(next, evictedCode);

                                // Add new pattern to LRU tracking with age 0, increment all others
                                updateLRUOnAdd(LRUMap, nextStr);

                                System.err.println("[ENCODE] Added new pattern '" + nextStr + "' with reused code " + evictedCode);
                                System.err.println("[ENCODE] LRU Map state: " + LRUMap);
                            }
                            break;
                        //case "lfu":
                        //    // Evict least frequently used entry
                        //    // (Implementation omitted for brevity)
                        //    break;
                        default:
                            // Unknown policy - treat as freeze
                            break;
                    }
                }
            
                // Start new current pattern with the last read character
                current = new StringBuilder().append(c);
            }
        }

        // Output final pattern if any
        if (current.length() > 0) {
            BinaryStdOut.write(dictionary.get(current), W);
        }

        // idk why we need this check, but without it the code breaks so fuck it
        if (nextCode >= (1 << W) && W < maxW) {
            W++;
        }
        
        // Write EOF code to signal end of compressed data
        BinaryStdOut.write(EOF_CODE, W);
        BinaryStdOut.close();
        
    }

    private static void expand() { 

        Header h = readHeader();

        // Initialize width
        int maxCode = 1 << h.maxW; 
        int W = h.minW;

        int EOF_CODE = h.alphabetSize;
        int nextCode = h.alphabetSize + 1; // Next available code index (skip EOF)
        int RESET_CODE = -1; // -1 for nonreset
        if (h.policy == 1) {
            RESET_CODE = h.alphabetSize + 1;
            nextCode++; // skip resetcode   
        }

        // LRU/LFU tracking structures
        // LRU: Map pattern string -> age (0 = most recently used, higher = older)
        // Excludes alphabet entries (we never evict those)
        Map<String, Integer> LRUMap = new HashMap<>();
        Map<String, Integer> LFUMap = new HashMap<>();

        // best data structure for decompression dictionary is an array
        String[] dictionary = new String[maxCode];
        for (int i = 0; i < h.alphabetSize; i++) {
            dictionary[i] = h.alphabet.get(i).toString();
        }

        // now let's start reading the compressed data and decompressing

        // Handle empty compressed data
        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        // handle first codeword
        int current = BinaryStdIn.readInt(W);
        // Check if first code is EOF (empty file)
        if (current == EOF_CODE) {
            BinaryStdOut.close();
            return;
        }

        if (current < h.alphabetSize) { // should be in initial dictionary
            BinaryStdOut.write(dictionary[current]);
        } else {
            System.err.println("Bad compressed code: " + current);
            System.exit(1);
        }
        String valPrior = dictionary[current]; // need this for later building

        while (!BinaryStdIn.isEmpty()) { 

            // Check if width needs to increase BEFORE reading next code
            if (nextCode >= (1 << W) && W < h.maxW) {
                W++;
            }
            
            current = BinaryStdIn.readInt(W);

            // Check for EOF code
            if (current == EOF_CODE) {
                break;
            }

            if (h.policy == 1 && current == RESET_CODE) {
                // Clear the decoding table by nulling entries after alphabet
                // Reuse the existing array instead of allocating a new one
                for (int i = h.alphabetSize; i < dictionary.length; i++) {
                    dictionary[i] = null;
                }

                // Reset state variables back to initial values
                nextCode = h.alphabetSize + 2; // skip EOF and RESET_CODE
                W = h.minW;

                // Clear LRU tracking on reset
                LRUMap.clear();

                // don't need to check width increase because we just reset to minW

                // Read the next code after reset at minW width
                // The loop will continue normal decompression from here
                current = BinaryStdIn.readInt(W);

                // Check if next code is EOF (edge case: reset right before end)
                if (current == EOF_CODE) {
                    break;
                }

                // Output the decoded string and continue
                valPrior = dictionary[current];
                BinaryStdOut.write(valPrior);
                continue; // Skip pattern learning for this iteration
            }

            // Declare string for current entry (initialized in branches below)
            String s = "";

            if (current < nextCode) {
                // Code is already in dictionary
                s = dictionary[current];
            } else if (current == nextCode) {
                // Special case: code not in dictionary yet (cScSc problem)
                // string concat: s = valPrior + valPrior.charAt(0);
                // Use StringBuilder to avoid string concatenation overhead
                StringBuilder tempSb = new StringBuilder(valPrior.length() + 1);
                tempSb.append(valPrior).append(valPrior.charAt(0));
                s = tempSb.toString();
            } else {
                // Invalid code
                System.err.println("Bad compressed code: " + current);
                System.exit(1);
            }

            BinaryStdOut.write(s);

            // Update LRU: mark output pattern as used (if it's in LRU map)
            if (h.policy == 2) { // LRU policy
                if (LRUMap.containsKey(s)) {
                    incrementAllAges(LRUMap);
                    LRUMap.put(s, 0);
                    System.err.println("[DECODE] Used pattern '" + s + "' (code " + current + ") - age reset to 0");
                }
            }

            // Add new entry: previous string + first char of current string
            // Use StringBuilder instead of string concatenation for efficiency
            if (nextCode < maxCode) {
                // string concat: dictionary[nextCode++] = valPrior + s.charAt(0);
                StringBuilder tempSb = new StringBuilder(valPrior.length() + 1);
                tempSb.append(valPrior).append(s.charAt(0));
                String newEntry = tempSb.toString();
                dictionary[nextCode++] = newEntry;

                // Update LRU tracking for new entry
                if (h.policy == 2) { // LRU policy
                    updateLRUOnAdd(LRUMap, newEntry);
                    System.err.println("[DECODE] Added pattern '" + newEntry + "' (code " + (nextCode - 1) + ") with age 0");
                    System.err.println("[DECODE] LRU Map state: " + LRUMap);
                }
            } else {
                // Dictionary full - handle according to policy
                switch (h.policy) {
                    case 0: // freeze
                        // Do nothing - dictionary remains full
                        break;
                    case 1: // reset
                        // reset is handled above when we read RESET_CODE
                        break;

                    case 2: // lru
                        // Evict least recently used entry
                        // Find LRU entry (highest age, excluding alphabet)
                        String lruPattern = findLRUEntry(LRUMap);

                        if (lruPattern != null) {
                            // Find the code for the LRU pattern
                            int evictedCode = -1;
                            for (int i = h.alphabetSize; i < nextCode; i++) {
                                if (dictionary[i] != null && dictionary[i].equals(lruPattern)) {
                                    evictedCode = i;
                                    break;
                                }
                            }

                            if (evictedCode != -1) {
                                System.err.println("[DECODE] Dictionary full! Evicting LRU pattern: '" + lruPattern + "' (code " + evictedCode + ") with age " + LRUMap.get(lruPattern));

                                // Remove from dictionary
                                dictionary[evictedCode] = null;

                                // Remove from LRU tracking
                                LRUMap.remove(lruPattern);

                                // Add new entry with the evicted code
                                StringBuilder tempSb = new StringBuilder(valPrior.length() + 1);
                                tempSb.append(valPrior).append(s.charAt(0));
                                String newEntry = tempSb.toString();
                                dictionary[evictedCode] = newEntry;

                                // Add new pattern to LRU tracking with age 0, increment all others
                                updateLRUOnAdd(LRUMap, newEntry);

                                System.err.println("[DECODE] Added new pattern '" + newEntry + "' (code " + evictedCode + ") in place of evicted entry");
                                System.err.println("[DECODE] LRU Map state: " + LRUMap);
                            }
                        }
                        break;
                    //case 3: // lfu
                    //    // Evict least frequently used entry
                    //    // (Implementation omitted for brevity)
                    //    break;
                    default:
                        // Unknown policy - treat as freeze
                        break;
                }
            }

            valPrior = s; // Update previous string for next iteration
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
