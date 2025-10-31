import java.io.*;
import java.util.*;

/**
 * LZWTool is a configurable LZW compression and expansion utility.
 * Supports variable codeword widths and multiple codebook eviction policies (freeze, reset, LRU, LFU).
 * 
 * === note ===
 * Compress:
 * java LZWTool --mode compress --minW 9 --maxW 16 --policy lru --alphabet alphabets/ascii.txt < input > output
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
        BinaryStdOut.write(alphabet.size(), 16);

        for (String symbol : alphabet) {
            BinaryStdOut.write(symbol.length() > 0 ? symbol.charAt(0) : 0, 8);
        }
        // System.err.println("DEBUG writeHeader: Finished writing header");
    }

    /**
     * Represents the header of a compressed LZW file
     */
    private static class Header {
        int minW;
        int maxW;
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

        // Read policy code (even though we don't use it in expand)
        int policyCode = BinaryStdIn.readInt(8);
        // System.err.println("DEBUG readHeader: policyCode=" + policyCode);

        int alphabetSize = BinaryStdIn.readInt(16);
        // System.err.println("DEBUG readHeader: alphabetSize=" + alphabetSize);
        h.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++) {
            h.alphabet.add(String.valueOf(BinaryStdIn.readChar(8)));
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
        int nextCode = 0;

        for (String symbol : alphabet) {
            codebook.put(new StringBuilder(symbol), nextCode++);
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
                System.err.println("DEBUG compress: Writing code " + codebook.get(current) + " with W=" + W + ", nextCode=" + nextCode);
                BinaryStdOut.write(codebook.get(current), W);

                if (nextCode < maxCode) {
                    if (nextCode >= (1 << W) && W < maxW) {
                        System.err.println("DEBUG compress: Incrementing W from " + W + " to " + (W+1) + " because nextCode=" + nextCode + " >= " + (1<<W));
                        W++;
                    }
                    codebook.put(next, nextCode++);
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
            System.err.println("DEBUG compress: Writing final code " + codebook.get(current) + " with W=" + W);
            BinaryStdOut.write(codebook.get(current), W);
        }

        System.err.println("DEBUG compress: Writing EOF_CODE " + EOF_CODE + " with W=" + W);
        BinaryStdOut.write(EOF_CODE, W);
        BinaryStdOut.close();
    }

    /**
     * Expand compressed input from standard input and write to standard output
     */
    private static void expand() {
        Header h = readHeader();
        int alphabetSize = h.alphabet.size();
        int maxCode = 1 << h.maxW;

        String[] decodingTable = new String[maxCode];
        for (int i = 0; i < alphabetSize; i++) {
            decodingTable[i] = h.alphabet.get(i);
        }

        int EOF_CODE = alphabetSize;
        int nextCode = alphabetSize + 1;
        int W = h.minW;

        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        int prevCode = BinaryStdIn.readInt(W);
        if (prevCode == EOF_CODE) {
            BinaryStdOut.close();
            return;
        }

        String val = decodingTable[prevCode];
        BinaryStdOut.write(val);

        while (!BinaryStdIn.isEmpty()) {
            System.err.println("DEBUG expand loop: nextCode=" + nextCode + ", W=" + W + ", (1<<W)=" + (1<<W));
            if (nextCode >= (1 << W) && W < h.maxW) {
                System.err.println("DEBUG: Incrementing W from " + W + " to " + (W+1));
                W++;
            }

            System.err.println("DEBUG: About to read codeword with W=" + W);
            int codeword = BinaryStdIn.readInt(W);
            System.err.println("DEBUG: Read codeword=" + codeword + ", EOF_CODE=" + EOF_CODE);
            if (codeword == EOF_CODE) break;

            String s = decodingTable[codeword];
            if (s == null) {
                System.err.println("DEBUG: codeword " + codeword + " not in table, using special case");
                s = val + val.charAt(0);
            }

            BinaryStdOut.write(s);

            if (nextCode < maxCode) {
                System.err.println("DEBUG: Adding decodingTable[" + nextCode + "]");
                decodingTable[nextCode++] = val + s.charAt(0);
            }

            val = s;
        }

        BinaryStdOut.close();
    }
}
