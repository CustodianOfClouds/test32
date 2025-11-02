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


    private static void compress(int minW, int maxW, String policy, List<Character> alphabet) { 
        
        writeHeader(minW, maxW, policy, alphabet);

        // compression dictionary stored in TST, String -> Code/Integer
        TSTmod<Integer> dictionary = new TSTmod<>();

        // width tracking
        int W = minW; // Current codeword width in bits
        int maxCode = 1 << maxW; // Maximum number of codes (2^maxW)

        //// reset tracking structures
        //int RESET_CODE = -1;

        //// LRU/LFU tracking structures
        //Map<String, Integer> LRUMap = new HashMap<>();
        //Map<String, Integer> LFUMap = new HashMap<>();

        // create initial dictionary from alphabet
        // nextcode just tracks next available index that we can assign to new dictionary entries
        int nextCode = 0;
        for (Character symbol : alphabet) {
            dictionary.put(new StringBuilder(String.valueOf(symbol)), nextCode++);
        }

        // now let's start compressing!
        // raw binary data is piped in during actual execution, so we start reading from BinaryStdIn

        // Handle empty input
        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        // initialize the current pattern
        char c = BinaryStdIn.readChar();
        StringBuilder current = new StringBuilder().append(c);
        // Verify character is in alphabet
        if (dictionary.get(current) == null) {
            System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
            System.exit(1);
        }

        while (!BinaryStdIn.isEmpty()) {
  
            c = BinaryStdIn.readChar();
            // verify character is in alphabet
            if (dictionary.get(new StringBuilder().append(c)) == null) {
                System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
                System.exit(1);
            }
            StringBuilder next = new StringBuilder(current).append(c);
            
            if (dictionary.contains(next)) {
                // Pattern exists - extend current pattern
                current = next;

                // Update LRU/LFU tracking structures 

            } else { 

                // Pattern not in codebook - output current and add new pattern
                BinaryStdOut.write(dictionary.get(current), W);

                // log into dictionary, if space available
                if (nextCode < maxCode) {
                    // There's space in the dictionary - add new pattern
                    dictionary.put(next, nextCode++);
                    
                    //// Update LRU/LFU tracking structures 
                    //if (policy.equals("lru")) {
                    //    LRUMap.put(next.toString(), 0); // New entry, age 0
                    //} else if (policy.equals("lfu")) {
                    //    LFUMap.put(next.toString(), 0); // New entry, frequency 0
                    //}

                    // Check if we need to increase codeword width
                    if (nextCode == (1 << W) && W < maxW) {
                        W++;
                    }

                } else {
                    // Dictionary full - handle according to policy
                    switch (policy) {
                        case "freeze":
                            // Do nothing - dictionary remains full
                            break;
                        //case "reset":
                        //    // Reset dictionary to initial state
                        //    dictionary = new TSTmod<>();
                        //    nextCode = 0;
                        //    for (Character symbol : alphabet) {
                        //        dictionary.put(new StringBuilder(String.valueOf(symbol)), nextCode++);
                        //    }
                        //    W = minW; // Reset codeword width
                        //    break;
                        //case "lru":
                        //    // Evict least recently used entry
                        //    // (Implementation omitted for brevity)
                        //    break;
                        //case "lfu":
                        //    // Evict least frequently used entry
                        //    // (Implementation omitted for brevity)
                        //    break;
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

        BinaryStdOut.close();
        
    }

    private static void expand() { 

        Header h = readHeader();

        // Initialize width
        int maxCode = 1 << h.maxW; 
        int W = h.minW;

        int nextCode = h.alphabetSize; // Next available code index

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
        if (current < h.alphabetSize) { // should be in initial dictionary
            BinaryStdOut.write(dictionary[current]);
        } else {
            System.err.println("Bad compressed code: " + current);
            System.exit(1);
        }
        String valPrior = dictionary[current]; // need this for later building

        while (!BinaryStdIn.isEmpty()) {
            
            current = BinaryStdIn.readInt(W);
            String s = ""; // to hold the string for current entry

            if (current < nextCode) {
                // Code is already in dictionary
                s = dictionary[current];
            } else if (current == nextCode) {
                // Special case: code not in dictionary yet (cScSc problem)
                s = valPrior + valPrior.charAt(0);
            } else {
                System.err.println("Bad compressed code: " + current);
                System.exit(1);
            }

            BinaryStdOut.write(s);
    
            // Add new entry: previous string + first char of current string
            if (nextCode < maxCode) {

                dictionary[nextCode++] = valPrior + s.charAt(0);

                if (nextCode == (1 << W) && W < h.maxW) {
                    W++;
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
