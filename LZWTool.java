import java.io.*;
import java.util.*;

/**
 * LZWTool is a configurable LZW compression and expansion utility.
 * Supports variable codeword widths and multiple codebook eviction policies
 * (freeze, reset, LRU, LFU).
 * 
 * === note ===
 * Compress:
 * java LZWTool --mode compress --minW 9 --maxW 16 --policy lru --alphabet
 * alphabets/ascii.txt < input > output
 * Expand:
 * java LZWTool --mode expand < output > restored
 */
public class LZWTool {

    /** Compression or expansion mode */
    private static String mode;

    /** Minimum codeword width */
    private static int minW = 9;

    /** Maximum codeword width */
    private static int maxW = 16;

    /** Eviction policy when codebook is full: freeze, reset, lru, lfu */
    private static String policy = "freeze";

    /** Path to the alphabet file for initializing the codebook */
    private static String alphabetPath;

    /**
     * Entry point for LZWTool
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        parseArguments(args);

        if (mode.equals("compress")) {
            validateCompressionArgs();
            List<String> alphabet = loadAlphabet(alphabetPath);
            if (alphabet == null) {
                System.err.println("Error: Could not load alphabet from " + alphabetPath);
                System.exit(1);
            }
            compress(minW, maxW, policy, alphabet);
        } else if (mode.equals("expand")) {
            expand();
        } else {
            System.err.println("Error: --mode must be 'compress' or 'expand'");
            System.exit(1);
        }
    }

    /**
     * Parse command-line arguments
     *
     * @param args command-line arguments
     */
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

    /**
     * Validate required arguments for compression
     */
    private static void validateCompressionArgs() {
        if (alphabetPath == null || minW > maxW) {
            System.err.println("Error: Invalid arguments for compression");
            System.exit(1);
        }
    }

    /**
     * Load alphabet from file
     *
     * @param path path to the alphabet file
     * @return list of unique symbols in the alphabet, or null if error
     */
    private static List<String> loadAlphabet(String path) {
        List<String> alphabet = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(path), "UTF-8")) {
            StringBuilder lineBuffer = new StringBuilder();
            int c;
            boolean hasCRLF = false;
            boolean checkedLineEnding = false;

            while ((c = reader.read()) != -1) {
                if (c == '\n') {
                    if (!checkedLineEnding) {
                        if (lineBuffer.length() > 0 && lineBuffer.charAt(lineBuffer.length() - 1) == '\r') {
                            hasCRLF = true;
                        }
                        checkedLineEnding = true;
                    }

                    if (hasCRLF && lineBuffer.length() > 0 && lineBuffer.charAt(lineBuffer.length() - 1) == '\r') {
                        lineBuffer.setLength(lineBuffer.length() - 1);
                    }

                    String symbol = (lineBuffer.length() == 0) ? "\n" : String.valueOf(lineBuffer.charAt(0));
                    if (!seen.contains(symbol)) {
                        seen.add(symbol);
                        alphabet.add(symbol);
                    }
                    lineBuffer.setLength(0);
                } else {
                    lineBuffer.append((char) c);
                }
            }

            // Last line
            if (lineBuffer.length() > 0) {
                if (hasCRLF && lineBuffer.charAt(lineBuffer.length() - 1) == '\r') {
                    lineBuffer.setLength(lineBuffer.length() - 1);
                }
                String symbol = String.valueOf(lineBuffer.charAt(0));
                if (!seen.contains(symbol)) {
                    seen.add(symbol);
                    alphabet.add(symbol);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return alphabet;
    }

    /**
     * Write header to the compressed file
     *
     * @param minW     minimum codeword width
     * @param maxW     maximum codeword width
     * @param policy   codebook eviction policy
     * @param alphabet list of symbols in the alphabet
     */
    private static void writeHeader(int minW, int maxW, String policy, List<String> alphabet) {
        // System.err.println("DEBUG writeHeader: minW=" + minW + ", maxW=" + maxW + ", policy=" + policy + ", alphabet.size=" + alphabet.size());
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
                policyCode = 0;
                break;
        }
        // System.err.println("DEBUG writeHeader: policyCode=" + policyCode);
        BinaryStdOut.write(policyCode, 8);
        // System.err.println("DEBUG writeHeader: Writing alphabetSize=" + alphabet.size());
        BinaryStdOut.write(alphabet.size(), 16);

        for (int i = 0; i < alphabet.size(); i++) {
            String symbol = alphabet.get(i);
            char c = symbol.length() > 0 ? symbol.charAt(0) : 0;
            // System.err.println("DEBUG writeHeader: Writing alphabet char " + i + ": '" + c + "' (" + (int)c + ")");
            BinaryStdOut.write(c, 8);
        }
        // System.err.println("DEBUG writeHeader: Finished writing header");
    }

    /**
     * Represents the header of a compressed LZW file
     */
    private static class Header {
        int minW;
        int maxW;
        String policy;
        List<String> alphabet;
    }

    /**
     * Read the header from a compressed file
     *
     * @return Header object containing minW, maxW, policy, and alphabet
     */
    private static Header readHeader() {
        Header h = new Header();
        // System.err.println("DEBUG readHeader: Starting to read header");
        h.minW = BinaryStdIn.readInt(8);
        // System.err.println("DEBUG readHeader: minW=" + h.minW);
        h.maxW = BinaryStdIn.readInt(8);
        // System.err.println("DEBUG readHeader: maxW=" + h.maxW);

        // Read and decode policy
        int policyCode = BinaryStdIn.readInt(8);
        // System.err.println("DEBUG readHeader: policyCode=" + policyCode);
        switch (policyCode) {
            case 0:
                h.policy = "freeze";
                break;
            case 1:
                h.policy = "reset";
                break;
            case 2:
                h.policy = "lru";
                break;
            case 3:
                h.policy = "lfu";
                break;
            default:
                h.policy = "freeze";
                break;
        }

        int alphabetSize = BinaryStdIn.readInt(16);
        // System.err.println("DEBUG readHeader: alphabetSize=" + alphabetSize);
        h.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++) {
            char c = BinaryStdIn.readChar(8);
            // System.err.println("DEBUG readHeader: Read alphabet char " + i + ": '" + c + "' (" + (int)c + ")");
            h.alphabet.add(String.valueOf(c));
        }
        // System.err.println("DEBUG readHeader: Finished reading header, alphabet has " + h.alphabet.size() + " symbols");
        return h;
    }

    /**
     * Compress input from standard input and write to standard output
     *
     * @param minW     minimum codeword width
     * @param maxW     maximum codeword width
     * @param policy   codebook eviction policy
     * @param alphabet seed alphabet
     */
    private static void compress(int minW, int maxW, String policy, List<String> alphabet) {
        writeHeader(minW, maxW, policy, alphabet);

        TSTmod<Integer> codebook = new TSTmod<>();
        Map<Integer, String> codeToString = new HashMap<>();
        Map<Integer, Long> lastUsed = new HashMap<>();
        Map<Integer, Integer> useCount = new HashMap<>();
        long timestamp = 0;

        int nextCode = 0;
        int alphabetSize = alphabet.size();

        for (String symbol : alphabet) {
            codebook.put(new StringBuilder(symbol), nextCode);
            codeToString.put(nextCode, symbol);
            nextCode++;
        }

        int EOF_CODE = nextCode++;
        int W = minW;
        int maxCode = 1 << maxW;

        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        char c = BinaryStdIn.readChar();
        StringBuilder current = new StringBuilder().append(c);

        Integer firstCode = codebook.get(current);
        if (firstCode == null) {
            System.err.println("Error: Input contains byte value " + (int) c +
                    " (0x" + Integer.toHexString(c) + ") which is not in the alphabet");
            System.exit(1);
        }

        while (!BinaryStdIn.isEmpty()) {
            c = BinaryStdIn.readChar();
            StringBuilder next = new StringBuilder(current).append(c);

            if (codebook.contains(next)) {
                current = next;
            } else {
                int code = codebook.get(current);
                // System.err.println compress: Writing code " + code + " (width " + W + ") for string \"" + current + "\"");
                BinaryStdOut.write(code, W);

                // Track usage
                lastUsed.put(code, timestamp++);
                useCount.put(code, useCount.getOrDefault(code, 0) + 1);

                if (nextCode < maxCode) {
                    if (nextCode >= (1 << W) && W < maxW) {
                        // System.err.println("DEBUG compress: Increasing W from " + W + " to " + (W+1) + " at nextCode=" + nextCode);
                        W++;
                    }
                    // System.err.println("DEBUG compress: Adding code " + nextCode + " for string \"" + next + "\"");
                    codebook.put(next, nextCode);
                    codeToString.put(nextCode, next.toString());
                    nextCode++;
                } else {
                    // Codebook is full, apply policy
                    // System.err.println compress: Codebook full (nextCode=" + nextCode + ", maxCode=" + maxCode + "), applying policy: " + policy);
                    switch (policy) {
                        case "reset":
                            // Reset codebook to alphabet only
                            // System.err.println compress: Resetting codebook to alphabet");
                            codebook = new TSTmod<>();
                            codeToString.clear();
                            lastUsed.clear();
                            useCount.clear();
                            nextCode = 0;
                            for (String symbol : alphabet) {
                                codebook.put(new StringBuilder(symbol), nextCode);
                                codeToString.put(nextCode, symbol);
                                nextCode++;
                            }
                            nextCode++; // Skip EOF_CODE
                            W = minW;
                            // System.err.println("DEBUG compress: After reset: nextCode=" + nextCode + ", W=" + W);
                            break;

                        case "lru":
                            // Evict least recently used entry
                            int lruCode = findLRU(lastUsed, alphabetSize, EOF_CODE);
                            if (lruCode != -1) {
                                String lruString = codeToString.get(lruCode);
                                // Delete old mapping by setting value to null
                                codebook.put(new StringBuilder(lruString), null);
                                codeToString.remove(lruCode);
                                lastUsed.remove(lruCode);
                                useCount.remove(lruCode);

                                // Add new mapping with reused code
                                codebook.put(next, lruCode);
                                codeToString.put(lruCode, next.toString());
                            }
                            break;

                        case "lfu":
                            // Evict least frequently used entry
                            int lfuCode = findLFU(useCount, alphabetSize, EOF_CODE);
                            if (lfuCode != -1) {
                                String lfuString = codeToString.get(lfuCode);
                                // Delete old mapping by setting value to null
                                codebook.put(new StringBuilder(lfuString), null);
                                codeToString.remove(lfuCode);
                                lastUsed.remove(lfuCode);
                                useCount.remove(lfuCode);

                                // Add new mapping with reused code
                                codebook.put(next, lfuCode);
                                codeToString.put(lfuCode, next.toString());
                            }
                            break;

                        case "freeze":
                        default:
                            // Do nothing - stop adding new codes
                            break;
                    }
                }

                StringBuilder charCheck = new StringBuilder().append(c);
                if (!codebook.contains(charCheck)) {
                    System.err.println("Error: Input contains byte value " + (int) c +
                            " (0x" + Integer.toHexString(c) + ") which is not in the alphabet");
                    System.exit(1);
                }
                current = charCheck;
            }
        }

        if (current.length() > 0) {
            int code = codebook.get(current);
            // System.err.println compress: Writing final code " + code + " (width " + W + ") for string \"" + current + "\"");
            BinaryStdOut.write(code, W);
        }

        // System.err.println compress: Writing EOF code " + EOF_CODE + " (width " + W + ")");
        BinaryStdOut.write(EOF_CODE, W);
        BinaryStdOut.close();
    }

    /**
     * Find the least recently used code (excluding alphabet and EOF)
     */
    private static int findLRU(Map<Integer, Long> lastUsed, int alphabetSize, int eofCode) {
        int lruCode = -1;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<Integer, Long> entry : lastUsed.entrySet()) {
            int code = entry.getKey();
            if (code >= alphabetSize && code != eofCode) {
                if (entry.getValue() < oldestTime) {
                    oldestTime = entry.getValue();
                    lruCode = code;
                }
            }
        }

        return lruCode;
    }

    /**
     * Find the least frequently used code (excluding alphabet and EOF)
     */
    private static int findLFU(Map<Integer, Integer> useCount, int alphabetSize, int eofCode) {
        int lfuCode = -1;
        int minCount = Integer.MAX_VALUE;

        for (Map.Entry<Integer, Integer> entry : useCount.entrySet()) {
            int code = entry.getKey();
            if (code >= alphabetSize && code != eofCode) {
                if (entry.getValue() < minCount) {
                    minCount = entry.getValue();
                    lfuCode = code;
                }
            }
        }

        return lfuCode;
    }

    /**
     * Expand compressed input from standard input and write to standard output
     */
    private static void expand() {
        Header h = readHeader();
        int alphabetSize = h.alphabet.size();
        int maxCode = 1 << h.maxW;

        String[] decodingTable = new String[maxCode];
        Map<Integer, Long> lastUsed = new HashMap<>();
        Map<Integer, Integer> useCount = new HashMap<>();
        long timestamp = 0;

        for (int i = 0; i < alphabetSize; i++) {
            decodingTable[i] = h.alphabet.get(i);
        }

        int EOF_CODE = alphabetSize;
        int nextCode = alphabetSize + 1;
        int W = h.minW;

        // System.err.println expand: EOF_CODE=" + EOF_CODE + ", nextCode=" + nextCode + ", W=" + W + ", maxCode=" + maxCode);

        if (BinaryStdIn.isEmpty()) {
            // System.err.println expand: Input is empty, returning");
            BinaryStdOut.close();
            return;
        }

        // System.err.println expand: Reading first code with W=" + W);
        int prevCode = BinaryStdIn.readInt(W);
        // System.err.println expand: Read prevCode=" + prevCode);
        if (prevCode == EOF_CODE) {
            // System.err.println expand: First code is EOF, returning");
            BinaryStdOut.close();
            return;
        }

        String val = decodingTable[prevCode];
        // System.err.println expand: Outputting val=\"" + val + "\"");
        BinaryStdOut.write(val);

        // Track usage
        lastUsed.put(prevCode, timestamp++);
        useCount.put(prevCode, useCount.getOrDefault(prevCode, 0) + 1);

        while (!BinaryStdIn.isEmpty()) {
            int codeword = BinaryStdIn.readInt(W);
            if (codeword == EOF_CODE)
                break;

            String s = decodingTable[codeword];
            if (s == null)
                s = val + val.charAt(0);

            BinaryStdOut.write(s);

            // Track usage
            lastUsed.put(codeword, timestamp++);
            useCount.put(codeword, useCount.getOrDefault(codeword, 0) + 1);

            if (nextCode < maxCode) {
                // Increase W if needed BEFORE adding the new code
                if (nextCode >= (1 << W) && W < h.maxW)
                    W++;
                decodingTable[nextCode++] = val + s.charAt(0);
            } else {
                // Codebook is full, apply policy
                // System.err.println expand: Codebook full (nextCode=" + nextCode + ", maxCode=" + maxCode + "), applying policy: " + h.policy);
                switch (h.policy) {
                    case "reset":
                        // Reset codebook to alphabet only
                        // System.err.println expand: Resetting codebook to alphabet");
                        decodingTable = new String[maxCode];
                        for (int i = 0; i < alphabetSize; i++) {
                            decodingTable[i] = h.alphabet.get(i);
                        }
                        lastUsed.clear();
                        useCount.clear();
                        nextCode = alphabetSize + 1;
                        W = h.minW;
                        break;

                    case "lru":
                        // Evict least recently used entry
                        int lruCode = findLRU(lastUsed, alphabetSize, EOF_CODE);
                        if (lruCode != -1) {
                            decodingTable[lruCode] = val + s.charAt(0);
                            lastUsed.remove(lruCode);
                            useCount.remove(lruCode);
                        }
                        break;

                    case "lfu":
                        // Evict least frequently used entry
                        int lfuCode = findLFU(useCount, alphabetSize, EOF_CODE);
                        if (lfuCode != -1) {
                            decodingTable[lfuCode] = val + s.charAt(0);
                            lastUsed.remove(lfuCode);
                            useCount.remove(lfuCode);
                        }
                        break;

                    case "freeze":
                    default:
                        // Do nothing - stop adding new codes
                        break;
                }
            }

            val = s;
        }

        BinaryStdOut.close();
    }
}
